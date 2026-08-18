package main

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

type fetchedFile struct {
	Role   string `json:"role"`
	Arch   string `json:"arch,omitempty"`
	Name   string `json:"name"`
	URL    string `json:"url"`
	Path   string `json:"path"`
	Bytes  int64  `json:"bytes"`
	Status string `json:"status"`
	Error  string `json:"error,omitempty"`
}

type downloadJob struct {
	role, arch, url, orig, name string
	optional                    bool
}

func (e catalogEntry) downloadJobs(latest ghLatest) []downloadJob {
	oldTag := firstNonEmpty(e.Tag, e.Version)
	newTag := firstNonEmpty(latest.Tag, latest.Version)
	sameVer := oldTag != "" && newTag != "" &&
		normalizeVer(displayVersion(oldTag)) == normalizeVer(displayVersion(newTag))
	var jobs []downloadJob
	add := func(role, arch, raw, name string, optional bool) {
		raw = strings.TrimSpace(raw)
		url := raw
		if !sameVer {
			url = rewriteURL(raw, oldTag, e.Version, newTag)
		}
		url = collapseNestedReleaseTag(url)
		if url == "" || strings.HasPrefix(url, "apt://") {
			return
		}
		if name == "" {
			name = filepath.Base(url)
		}
		jobs = append(jobs, downloadJob{role, arch, url, raw, name, optional})
	}
	for _, a := range e.Artifacts {
		if a.isApt() {
			continue
		}
		add("artifact", "x86_64", a.URL, "", a.Optional)
		if a.URLAarch64 != "" && a.URLAarch64 != a.URL {
			add("artifact", "aarch64", a.URLAarch64, "", a.Optional)
		}
	}
	for _, a := range e.Configs {
		if a.isApt() {
			continue
		}
		add("config", "", a.URL, a.Name, a.Optional)
	}
	if !hasArtifactJob(jobs) {
		for _, a := range linuxReleaseAssets(latest.Assets) {
			add("artifact", a.arch, a.url, a.name, false)
		}
	}
	return jobs
}

func hasArtifactJob(jobs []downloadJob) bool {
	for _, j := range jobs {
		if j.role == "artifact" {
			return true
		}
	}
	return false
}

func linuxReleaseAssets(assets []ghAsset) []struct{ arch, url, name string } {
	var out []struct{ arch, url, name string }
	for _, a := range assets {
		name := strings.TrimSpace(a.Name)
		url := strings.TrimSpace(a.BrowserDownloadURL)
		if name == "" || url == "" || skipReleaseAsset(name) {
			continue
		}
		low := strings.ToLower(name)
		arch := ""
		switch {
		case strings.Contains(low, "aarch64") || strings.Contains(low, "arm64"):
			arch = "aarch64"
		case strings.Contains(low, "x86_64") || strings.Contains(low, "amd64") || strings.Contains(low, "x64"):
			arch = "x86_64"
		case strings.Contains(low, "linux"):
			arch = "x86_64"
		default:
			continue
		}
		out = append(out, struct{ arch, url, name string }{arch, url, name})
	}
	return out
}

func skipReleaseAsset(name string) bool {
	low := strings.ToLower(name)
	for _, s := range []string{".asc", ".sig", ".minisig", ".sha256", ".sha256sum", ".attestation", ".dmg", ".exe"} {
		if strings.HasSuffix(low, s) {
			return true
		}
	}
	for _, s := range []string{"windows", "darwin", "macos", "osx", "apple"} {
		if strings.Contains(low, s) {
			return true
		}
	}
	return false
}

func rewriteURL(url, oldTag, oldVer, newTag string) string {
	url = strings.TrimSpace(url)
	if url == "" || newTag == "" {
		return url
	}
	oldDisp := displayVersion(firstNonEmpty(oldTag, oldVer))
	newDisp := displayVersion(newTag)
	if oldDisp != "" && normalizeVer(oldDisp) == normalizeVer(newDisp) {
		return url
	}
	type pair struct{ old, neu string }
	var pairs []pair
	add := func(old, neu string) {
		old, neu = strings.TrimSpace(old), strings.TrimSpace(neu)
		if old == "" || neu == "" || old == neu {
			return
		}
		if strings.Contains(neu, old) {
			return
		}
		pairs = append(pairs, pair{old, neu})
	}
	oldPref := strings.TrimSuffix(strings.TrimSpace(oldTag), oldDisp)
	newPref := strings.TrimSuffix(strings.TrimSpace(newTag), newDisp)
	if oldTag != "" && newTag != "" && oldPref == newPref {
		add(oldTag, newTag)
	}
	add(oldDisp, newDisp)
	add(displayVersion(oldVer), newDisp)
	for i := 0; i < len(pairs); i++ {
		for j := i + 1; j < len(pairs); j++ {
			if len(pairs[j].old) > len(pairs[i].old) {
				pairs[i], pairs[j] = pairs[j], pairs[i]
			}
		}
	}
	out := url
	for _, p := range pairs {
		out = strings.ReplaceAll(out, p.old, p.neu)
	}
	return collapseNestedReleaseTag(out)
}

func collapseNestedReleaseTag(raw string) string {
	out := raw
	for i := 0; i < 8; i++ {
		next := strings.ReplaceAll(out, "GreatVoyage-vGreatVoyage-v", "GreatVoyage-v")
		next = strings.ReplaceAll(next, "GreatVoyage-Nile-GreatVoyage-Nile-", "GreatVoyage-Nile-")
		if next == out {
			return next
		}
		out = next
	}
	return out
}

func updateDir(clientsDir string, e catalogEntry, ver string) string {
	return filepath.Join(clientsDir, "_updates", e.Network, e.Env, ver)
}

func pinDir(clientsDir string, e catalogEntry) string {
	return filepath.Join(clientsDir, e.Network, e.Env)
}

func jobRelPath(j downloadJob) string {
	if j.role == "config" {
		return filepath.Join("conf", j.name)
	}
	if j.arch != "" {
		return filepath.Join("dist", j.arch, j.name)
	}
	return filepath.Join("dist", j.name)
}

func jobExists(root string, j downloadJob) bool {
	candidates := []string{filepath.Join(root, jobRelPath(j))}
	if j.role != "config" {
		candidates = append(candidates, filepath.Join(root, "dist", j.name))
	}
	for _, p := range candidates {
		fi, err := os.Stat(p)
		if err == nil && fi.Size() > 0 {
			return true
		}
	}
	return false
}

func jobsReady(root string, jobs []downloadJob) bool {
	if strings.TrimSpace(root) == "" {
		return false
	}
	if len(jobs) == 0 {
		return false
	}
	for _, j := range jobs {
		if j.optional {
			continue
		}
		if !jobExists(root, j) {
			return false
		}
	}
	return true
}

func versionOnDisk(clientsDir string, e catalogEntry, ver string, jobs []downloadJob) bool {
	if jobsReady(updateDir(clientsDir, e, ver), jobs) {
		return true
	}
	if pin := e.pin(); pin != "" && normalizeVer(pin) == normalizeVer(ver) {
		return jobsReady(pinDir(clientsDir, e), jobs)
	}
	return false
}

func downloadUpdate(clientsDir, publicBase string, e catalogEntry, latest ghLatest, token string) (dir string, public string, files []fetchedFile, err error) {
	jobs := e.downloadJobs(latest)
	ver := firstNonEmpty(latest.Version, latest.Tag)
	if ver == "" {
		ver = "unknown"
	}
	dir = updateDir(clientsDir, e, ver)
	if err = os.MkdirAll(dir, 0o755); err != nil {
		return "", "", nil, err
	}
	client := &http.Client{Timeout: 30 * time.Minute}
	for _, job := range jobs {
		out := filepath.Join(dir, jobRelPath(job))
		rec := fetchedFile{Role: job.role, Arch: job.arch, Name: job.name, URL: job.url, Path: out}
		if jobExists(dir, job) {
			if fi, stErr := os.Stat(out); stErr == nil {
				rec.Status = "ok"
				rec.Bytes = fi.Size()
			} else {
				rec.Status = "ok"
			}
			files = append(files, rec)
			continue
		}
		log.Printf("качаю %s %s", e.id(), job.url)
		n, dlErr := downloadFile(client, job.url, out, token)
		if dlErr != nil && job.orig != "" && job.orig != job.url {
			if n2, err2 := downloadFile(client, job.orig, out, token); err2 == nil {
				n, dlErr = n2, nil
				rec.URL = job.orig
			}
		}
		if dlErr != nil {
			rec.Status = "fail"
			rec.Error = dlErr.Error()
			if !job.optional {
				err = dlErr
			}
		} else {
			rec.Status = "ok"
			rec.Bytes = n
		}
		files = append(files, rec)
	}
	writeFetchedNote(dir, e, latest, files, "")
	public = publicUpdateURL(publicBase, e.Network, e.Env, ver)
	return dir, public, files, err
}

func writeFetchedNote(dir string, e catalogEntry, latest ghLatest, files []fetchedFile, note string) {
	if files == nil {
		files = []fetchedFile{}
	}
	body := map[string]any{
		"network":    e.Network,
		"env":        e.Env,
		"pin":        e.pin(),
		"latest":     latest.Version,
		"tag":        latest.Tag,
		"fetched_at": time.Now().UTC().Format(time.RFC3339),
		"files":      files,
	}
	if note != "" {
		body["note"] = note
	}
	raw, _ := json.MarshalIndent(body, "", "  ")
	_ = os.WriteFile(filepath.Join(dir, "fetched.json"), raw, 0o644)
}

func publicUpdateURL(publicBase, network, env, ver string) string {
	base := strings.TrimRight(strings.TrimSpace(publicBase), "/")
	if base == "" {
		return ""
	}
	return base + "/clients/_updates/" + network + "/" + env + "/" + ver + "/"
}

func downloadFile(client *http.Client, raw, dest, token string) (int64, error) {
	req, err := http.NewRequest(http.MethodGet, raw, nil)
	if err != nil {
		return 0, err
	}
	req.Header.Set("User-Agent", "rpcnode-client-watch")
	req.Header.Set("Accept", "*/*")
	host := strings.ToLower(req.URL.Host)
	if strings.Contains(host, "github.com") || strings.Contains(host, "githubusercontent.com") {
		req.Header.Set("Accept", "application/octet-stream")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
		}
	}
	resp, err := client.Do(req)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return 0, fmt.Errorf("HTTP %d %s", resp.StatusCode, raw)
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o755); err != nil {
		return 0, err
	}
	tmp := dest + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return 0, err
	}
	total := resp.ContentLength
	if total > 0 {
		log.Printf("  размер %.1f MiB", float64(total)/(1024*1024))
	}
	n, err := io.Copy(f, &progressReader{r: resp.Body, total: total})
	cerr := f.Close()
	if err != nil {
		_ = os.Remove(tmp)
		return 0, err
	}
	if cerr != nil {
		_ = os.Remove(tmp)
		return 0, cerr
	}
	if n == 0 {
		_ = os.Remove(tmp)
		return 0, fmt.Errorf("пустой файл %s", raw)
	}
	_ = os.Remove(dest)
	if err := os.Rename(tmp, dest); err != nil {
		return 0, err
	}
	return n, nil
}

type progressReader struct {
	r     io.Reader
	got   int64
	total int64
	last  int64
}

func (p *progressReader) Read(b []byte) (int, error) {
	n, err := p.r.Read(b)
	p.got += int64(n)
	if p.got-p.last >= 16<<20 || (err == io.EOF && p.got > p.last) {
		p.last = p.got
		if p.total > 0 {
			log.Printf("  %.0f%%  %.1f / %.1f MiB", 100*float64(p.got)/float64(p.total), float64(p.got)/(1024*1024), float64(p.total)/(1024*1024))
		} else {
			log.Printf("  %.1f MiB", float64(p.got)/(1024*1024))
		}
	}
	return n, err
}
