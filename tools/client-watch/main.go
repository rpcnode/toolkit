package main

import (
	"flag"
	"fmt"
	"log"
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
	flag.Parse()

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
		githubTok:  strings.TrimSpace(os.Getenv("GITHUB_TOKEN")),
		interval:   *interval,
		state:      st,
	}

	if *once {
		rows, err := w.listVersions()
		if err != nil {
			log.Fatal(err)
		}
		printVersionTable(rows)
		return
	}

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
		row := versionRow{ID: e.id(), Pin: e.pin(), Status: "no-github"}
		repo, prefix, ok := e.githubHint()
		if !ok {
			out = append(out, row)
			continue
		}
		row.Repo = repo
		key := repo + "|" + prefix
		latest, cached := cache[key]
		if !cached {
			if prev, bad := cacheErr[key]; bad {
				row.Status = "error"
				row.Error = prev.Error()
				out = append(out, row)
				continue
			}
			got, ghErr := fetchLatest(client, repo, prefix, w.githubTok)
			if ghErr != nil {
				cacheErr[key] = ghErr
				row.Status = "error"
				row.Error = ghErr.Error()
				out = append(out, row)
				continue
			}
			latest = got
			cache[key] = got
		}
		row.Latest = firstNonEmpty(latest.Version, latest.Tag)
		row.Tag = latest.Tag
		switch {
		case row.Latest == "":
			row.Status = "unknown"
		case row.Pin != "" && normalizeVer(row.Latest) == normalizeVer(row.Pin):
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
	var firstErr error
	found := 0
	for _, e := range cat.Entries {
		repo, prefix, ok := e.githubHint()
		if !ok {
			continue
		}
		key := repo + "|" + prefix
		latest, cached := cache[key]
		if !cached {
			got, ghErr := fetchLatest(client, repo, prefix, w.githubTok)
			if ghErr != nil {
				log.Printf("%s github %s: %v", e.id(), repo, ghErr)
				if firstErr == nil {
					firstErr = ghErr
				}
				continue
			}
			latest = got
			cache[key] = got
		}
		if latest.Tag == "" && latest.Version == "" {
			continue
		}
		pin := e.pin()
		if pin != "" && normalizeVer(latest.Version) == normalizeVer(pin) {
			continue
		}
		if seen, ok := st.Seen[e.id()]; ok && normalizeVer(seen.Tag) == normalizeVer(latest.Tag) {
			continue
		}
		found++
		public := ""
		dlNote := "скачивать нечего (apt/host)"
		if jobs := e.downloadJobs(latest.Tag); len(jobs) > 0 {
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
		msg := formatUpdate(e, latest)
		if st.TelegramToken != "" && st.TelegramChat != "" {
			if tgErr := sendTelegram(st.TelegramToken, st.TelegramChat, msg); tgErr != nil {
				log.Printf("telegram %s: %v", e.id(), tgErr)
				if firstErr == nil {
					firstErr = tgErr
				}
			}
		} else {
			log.Printf("telegram не настроен: %s", e.id())
		}
		if err := w.state.markSeen(e.id(), latest, public); err != nil {
			log.Printf("state: %v", err)
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
