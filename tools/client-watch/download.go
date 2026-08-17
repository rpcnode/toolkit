package main

import (
	"encoding/json"
	"fmt"
	"io"
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

func (e catalogEntry) downloadJobs(latestTag string) []struct {
	role, arch, url, name string
	optional              bool
} {
	oldTag := strings.TrimSpace(e.Tag)
	var jobs []struct {
		role, arch, url, name string
		optional              bool
	}
	add := func(role, arch, raw, name string, optional bool) {
		url := rewriteURL(raw, oldTag, latestTag)
		if url == "" || strings.HasPrefix(url, "apt://") {
			return
		}
		if name == "" {
			name = filepath.Base(url)
		}
		jobs = append(jobs, struct {
			role, arch, url, name string
			optional              bool
		}{role, arch, url, name, optional})
	}
	for _, a := range e.Artifacts {
		if a.isApt() {
			continue
		}
		add("artifact", "x86_64", a.URL, a.Name, a.Optional)
		if a.URLAarch64 != "" && a.URLAarch64 != a.URL {
			add("artifact", "aarch64", a.URLAarch64, a.Name, a.Optional)
		}
	}
	for _, a := range e.Configs {
		if a.isApt() {
			continue
		}
		add("config", "", a.URL, a.Name, a.Optional)
	}
	return jobs
}

func rewriteURL(url, oldTag, newTag string) string {
	url = strings.TrimSpace(url)
	if url == "" || oldTag == "" || newTag == "" || oldTag == newTag {
		return url
	}
	return strings.ReplaceAll(url, oldTag, newTag)
}

func downloadUpdate(clientsDir, publicBase string, e catalogEntry, latest ghLatest, token string) (dir string, public string, files []fetchedFile, err error) {
	jobs := e.downloadJobs(latest.Tag)
	ver := firstNonEmpty(latest.Version, latest.Tag)
	if ver == "" {
		ver = "unknown"
	}
	dir = filepath.Join(clientsDir, "_updates", e.Network, e.Env, ver)
	if err = os.MkdirAll(dir, 0o755); err != nil {
		return "", "", nil, err
	}
	client := &http.Client{Timeout: 30 * time.Minute}
	for _, job := range jobs {
		folder := "dist"
		if job.role == "config" {
			folder = "conf"
		} else if job.arch != "" {
			folder = filepath.Join("dist", job.arch)
		}
		out := filepath.Join(dir, folder, job.name)
		rec := fetchedFile{Role: job.role, Arch: job.arch, Name: job.name, URL: job.url, Path: out}
		n, dlErr := downloadFile(client, job.url, out, token)
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
	note, _ := json.MarshalIndent(map[string]any{
		"network":    e.Network,
		"env":        e.Env,
		"pin":        e.pin(),
		"latest":     latest.Version,
		"tag":        latest.Tag,
		"fetched_at": time.Now().UTC().Format(time.RFC3339),
		"files":      files,
	}, "", "  ")
	_ = os.WriteFile(filepath.Join(dir, "fetched.json"), note, 0o644)
	public = publicUpdateURL(publicBase, e.Network, e.Env, ver)
	return dir, public, files, err
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
	n, err := io.Copy(f, resp.Body)
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
