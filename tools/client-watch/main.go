package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type watcher struct {
	listen     string
	catalog    string
	clients    string
	publicBase string
	apiToken   string
	githubTok  string
	interval   time.Duration
	state      *stateStore

	checkMu sync.Mutex
}

func main() {
	log.SetFlags(log.LstdFlags | log.Lmsgprefix)
	log.SetPrefix("client-watch ")

	listen := flag.String("listen", env("CLIENT_WATCH_LISTEN", "127.0.0.1:8094"), "HTTP bind")
	catalog := flag.String("catalog", env("CLIENT_WATCH_CATALOG", ""), "path to clients/catalog.json")
	clients := flag.String("clients", env("CLIENT_WATCH_CLIENTS", ""), "clients dir (catalog parent if empty)")
	public := flag.String("public-base", env("CLIENT_WATCH_PUBLIC_BASE", "https://rpcnode.dev/install"), "public install URL")
	statePath := flag.String("state", env("CLIENT_WATCH_STATE", ""), "state.json (telegram + seen)")
	interval := flag.Duration("interval", parseDuration(env("CLIENT_WATCH_INTERVAL", "1h"), time.Hour), "check interval")
	apiToken := flag.String("api-token", env("CLIENT_WATCH_TOKEN", ""), "optional Bearer for /api/v1/*")
	once := flag.Bool("once", false, "print pin vs latest for every catalog row and exit")
	check := flag.Bool("check", false, "one poll: download new into _updates and exit")
	showVer := flag.Bool("version", false, "print version and exit")
	flag.Parse()

	if *showVer {
		fmt.Printf("rpcnode-client-watch version=%s api=%d\n", watchVersion, watchAPI)
		return
	}

	if strings.TrimSpace(*catalog) == "" {
		log.Fatal("укажи -catalog или CLIENT_WATCH_CATALOG")
	}
	clientsDir := strings.TrimSpace(*clients)
	if clientsDir == "" {
		clientsDir = filepath.Dir(*catalog)
	}
	stPath := strings.TrimSpace(*statePath)
	if stPath == "" {
		stPath = filepath.Join(clientsDir, ".watch", "state.json")
	}
	st, err := loadState(stPath)
	if err != nil {
		log.Fatalf("state: %v", err)
	}

	w := &watcher{
		listen:     *listen,
		catalog:    *catalog,
		clients:    clientsDir,
		publicBase: strings.TrimRight(*public, "/"),
		apiToken:   strings.TrimSpace(*apiToken),
		githubTok:  githubTokenFromEnv(),
		interval:   *interval,
		state:      st,
	}

	if *once {
		if w.githubTok == "" {
			fmt.Fprintln(os.Stderr, "github token: нет — будет HTTP 403. Впиши GITHUB_TOKEN в client-watch.env и запускай с --env-file client-watch.env")
		} else {
			fmt.Fprintf(os.Stderr, "github token: да (%d символов)\n", len(w.githubTok))
		}
		rows, err := w.listVersions()
		if err != nil {
			log.Fatal(err)
		}
		printVersionTable(rows)
		return
	}
	if *check {
		tok, chat := telegramCreds(w.state.snapshot())
		if tok == "" || chat == "" {
			log.Printf("telegram: нет — впиши TELEGRAM_BOT_TOKEN и TELEGRAM_CHAT в client-watch.env")
		} else {
			log.Printf("telegram: да chat=%s", chat)
		}
		if err := w.checkOnce(); err != nil {
			log.Fatal(err)
		}
		return
	}

	if tok, chat := telegramCreds(w.state.snapshot()); tok == "" || chat == "" {
		log.Printf("telegram: нет — systemd читает /etc/rpcnode/client-watch.env (TELEGRAM_BOT_TOKEN + TELEGRAM_CHAT), не файл в toolkit")
	} else {
		log.Printf("telegram: да chat=%s", chat)
		_ = w.state.setTelegram(tok, chat)
	}

	log.Printf("демон version=%s api=%d listen=%s catalog=%s clients=%s", watchVersion, watchAPI, w.listen, w.catalog, w.clients)
	log.Printf("это HTTP-сервер, он не печатает таблицу. таблица: ./rpcnode-client-watch -once  |  одна проверка: -check  |  обновить юнит: ./update.sh")
	go w.loop()
	if err := w.serveHTTP(); err != nil {
		log.Fatal(err)
	}
}

type versionRow struct {
	ID     string `json:"id"`
	Pin    string `json:"pin"`
	Latest string `json:"latest"`
	Tag    string `json:"tag,omitempty"`
	Repo   string `json:"repo,omitempty"`
	Status string `json:"status"`
	Error  string `json:"error,omitempty"`
}

func (w *watcher) listVersions() ([]versionRow, error) {
	cat, err := loadCatalog(w.catalog)
	if err != nil {
		return nil, err
	}
	client := httpClient()
	cache := map[string]ghLatest{}
	cacheErr := map[string]error{}
	out := make([]versionRow, 0, len(cat.Entries))
	for _, e := range cat.Entries {
		row := versionRow{ID: e.id(), Pin: e.pin(), Status: "no-source"}
		latest, src, err := w.lookupLatest(e, client, cache, cacheErr)
		if src != "" {
			row.Repo = src
		}
		if err != nil {
			row.Status = "error"
			row.Error = err.Error()
			out = append(out, row)
			continue
		}
		if latest.Version == "" && latest.Tag == "" {
			out = append(out, row)
			continue
		}
		row.Latest = firstNonEmpty(latest.Version, latest.Tag)
		row.Tag = latest.Tag
		switch {
		case row.Latest == "":
			row.Status = "unknown"
		case row.Pin != "" && sameVersion(row.Latest, row.Pin):
			row.Status = "ok"
		case row.Pin == "":
			row.Status = "new"
		default:
			row.Status = "update"
		}
		out = append(out, row)
	}
	return out, nil
}

func (w *watcher) lookupLatest(e catalogEntry, client *http.Client, cache map[string]ghLatest, cacheErr map[string]error) (ghLatest, string, error) {
	if repo, prefix, ok := e.githubHint(); ok {
		key := repo + "|" + prefix
		if prev, bad := cacheErr[key]; bad {
			return ghLatest{}, repo, prev
		}
		if got, cached := cache[key]; cached {
			return got, repo, nil
		}
		got, err := fetchLatest(client, repo, prefix, w.githubTok)
		if err != nil {
			cacheErr[key] = err
			return ghLatest{}, repo, err
		}
		cache[key] = got
		return got, repo, nil
	}
	if u := e.httpProbeURL(); u != "" {
		if prev, bad := cacheErr[u]; bad {
			return ghLatest{}, u, prev
		}
		if got, cached := cache[u]; cached {
			return got, u, nil
		}
		got, err := probeHTTP(client, u)
		if err != nil {
			cacheErr[u] = err
			return ghLatest{}, u, err
		}
		cache[u] = got
		return got, u, nil
	}
	return ghLatest{}, "", nil
}

func printVersionTable(rows []versionRow) {
	for _, r := range rows {
		pin := r.Pin
		if pin == "" {
			pin = "—"
		}
		latest := r.Latest
		if latest == "" {
			latest = "—"
		}
		line := fmt.Sprintf("%-22s  pin=%-16s  latest=%-16s  %s", r.ID, pin, latest, r.Status)
		if r.Error != "" {
			line += "  " + r.Error
		}
		fmt.Println(line)
	}
}

func (w *watcher) loop() {
	time.Sleep(3 * time.Second)
	if err := w.checkOnce(); err != nil {
		log.Printf("check: %v", err)
	}
	t := time.NewTicker(w.interval)
	defer t.Stop()
	for range t.C {
		if err := w.checkOnce(); err != nil {
			log.Printf("check: %v", err)
		}
	}
}

func (w *watcher) checkOnce() error {
	w.checkMu.Lock()
	defer w.checkMu.Unlock()

	cat, err := loadCatalog(w.catalog)
	if err != nil {
		_ = w.state.markCheck(err)
		return err
	}
	st := w.state.snapshot()
	client := httpClient()
	cache := map[string]ghLatest{}
	cacheErr := map[string]error{}
	var firstErr error
	found := 0
	log.Printf("проверка %d профилей…", len(cat.Entries))
	for _, e := range cat.Entries {
		log.Printf("%s: смотрю latest…", e.id())
		latest, _, lookErr := w.lookupLatest(e, client, cache, cacheErr)
		if lookErr != nil {
			log.Printf("%s: %v", e.id(), lookErr)
			if firstErr == nil {
				firstErr = lookErr
			}
			continue
		}
		if latest.Tag == "" && latest.Version == "" {
			continue
		}
		pin := e.pin()
		ver := firstNonEmpty(latest.Version, latest.Tag)
		jobs := e.downloadJobs(latest)
		isNew := pin == "" || !sameVersion(ver, pin)
		seenSame := false
		if seen, ok := st.Seen[e.id()]; ok && sameVersion(seen.Tag, latest.Tag) {
			seenSame = true
		}
		onDisk := len(jobs) > 0 && versionOnDisk(w.clients, e, ver, jobs)
		if !isNew && (len(jobs) == 0 || onDisk) {
			continue
		}
		if isNew && seenSame && (len(jobs) == 0 || onDisk) {
			continue
		}
		found++
		public := ""
		dlNote := "версия " + ver
		if len(jobs) == 0 {
			dlNote = "версия " + ver + " — качать нечего (клиент ставит агент с docker/host, не tarball)"
		} else if onDisk {
			dlNote = "уже на диске"
		} else {
			dir, pub, files, dlErr := downloadUpdate(w.clients, w.publicBase, e, latest, w.githubTok)
			public = pub
			okN := 0
			for _, f := range files {
				if f.Status == "ok" {
					okN++
				}
			}
			if dlErr != nil {
				dlNote = fmt.Sprintf("скачивание с ошибкой (%d ок): %v", okN, dlErr)
				if firstErr == nil {
					firstErr = dlErr
				}
			} else {
				dlNote = fmt.Sprintf("скачано %d файл(ов) → %s", okN, dir)
			}
		}
		if isNew {
			msg := formatUpdate(e, latest)
			tgTok, tgChat := telegramCreds(st)
			if tgTok != "" && tgChat != "" {
				if seen, ok := st.Seen[e.id()]; ok && sameVersion(seen.Tag, latest.Tag) {
					// already notified
				} else if tgErr := sendTelegram(tgTok, tgChat, msg); tgErr != nil {
					log.Printf("telegram %s: %v", e.id(), tgErr)
					if firstErr == nil {
						firstErr = tgErr
					}
				}
			} else if _, ok := st.Seen[e.id()]; !ok || !sameVersion(st.Seen[e.id()].Tag, latest.Tag) {
				log.Printf("telegram не настроен: %s", e.id())
			}
			if err := w.state.markSeen(e.id(), latest, public); err != nil {
				log.Printf("state: %v", err)
			}
		}
		log.Printf("%s pin=%s latest=%s %s", e.id(), pin, latest.Version, dlNote)
	}
	if err := w.state.markCheck(firstErr); err != nil {
		log.Printf("state: %v", err)
	}
	if found == 0 {
		log.Printf("новых версий нет (%d профилей)", len(cat.Entries))
	}
	return firstErr
}

func formatUpdate(e catalogEntry, latest ghLatest) string {
	ver := firstNonEmpty(latest.Version, latest.Tag)
	if ver == "" {
		return e.id() + " — вышла новая версия"
	}
	return e.id() + " — вышла новая версия " + ver
}

func githubTokenFromEnv() string {
	for _, key := range []string{"GITHUB_TOKEN", "GH_TOKEN"} {
		tok := strings.TrimSpace(os.Getenv(key))
		tok = strings.Trim(tok, "\"'")
		if tok != "" {
			return tok
		}
	}
	return ""
}

func env(key, fallback string) string {
	if v := strings.TrimSpace(os.Getenv(key)); v != "" {
		return v
	}
	return fallback
}

func parseDuration(raw string, fallback time.Duration) time.Duration {
	d, err := time.ParseDuration(raw)
	if err != nil || d <= 0 {
		return fallback
	}
	return d
}
