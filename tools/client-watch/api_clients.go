package main

import (
	"log"
	"net/http"
	"os"
	"path"
	"path/filepath"
	"strings"
)

type clientFileDTO struct {
	Role     string `json:"role"`
	Arch     string `json:"arch,omitempty"`
	Name     string `json:"name"`
	URL      string `json:"url"`
	Upstream string `json:"upstream,omitempty"`
	Rel      string `json:"rel,omitempty"`
	Ready    bool   `json:"ready"`
	Bytes    int64  `json:"bytes,omitempty"`
}

type clientPackDTO struct {
	ID      string          `json:"id"`
	Network string          `json:"network"`
	Env     string          `json:"env"`
	Pin     string          `json:"pin"`
	Latest  string          `json:"latest"`
	Tag     string          `json:"tag,omitempty"`
	Status  string          `json:"status"`
	Error   string          `json:"error,omitempty"`
	Notes   string          `json:"notes,omitempty"`
	Files   []clientFileDTO `json:"files"`
	Entry   catalogEntry    `json:"entry"`
}

func (w *watcher) handleClients(rw http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		rw.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	if r.URL.Query().Get("refresh") == "1" {
		if err := w.checkOnce(); err != nil {
			log.Printf("refresh: %v", err)
		}
	} else if !w.state.hasLatest() {
		go func() {
			if err := w.checkOnce(); err != nil {
				log.Printf("check: %v", err)
			}
		}()
	}
	packs, err := w.listClientPacks()
	if err != nil {
		writeJSON(rw, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	st := w.state.snapshot()
	writeJSON(rw, http.StatusOK, map[string]any{
		"ok":         true,
		"api":        watchAPI,
		"version":    watchVersion,
		"cached":     true,
		"last_check": st.LastCheck,
		"entries":    packs,
	})
}

func (w *watcher) handleFiles(rw http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		rw.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	rel := strings.TrimPrefix(r.URL.Path, "/files/")
	rel = path.Clean("/" + rel)
	rel = strings.TrimPrefix(rel, "/")
	if rel == "" || rel == "." || strings.HasPrefix(rel, "..") || strings.Contains(rel, "..") {
		http.NotFound(rw, r)
		return
	}
	root := filepath.Clean(w.clients)
	full := filepath.Clean(filepath.Join(root, filepath.FromSlash(rel)))
	sep := string(os.PathSeparator)
	if full != root && !strings.HasPrefix(full, root+sep) {
		http.Error(rw, "forbidden", http.StatusForbidden)
		return
	}
	http.ServeFile(rw, r, full)
}

func (w *watcher) listClientPacks() ([]clientPackDTO, error) {
	cat, err := loadCatalog(w.catalog)
	if err != nil {
		return nil, err
	}
	st := w.state.snapshot()
	out := make([]clientPackDTO, 0, len(cat.Entries))
	for _, e := range cat.Entries {
		out = append(out, w.packFromCache(e, cachedOf(st, e.id())))
	}
	return out, nil
}

func (w *watcher) packFromCache(e catalogEntry, cached cachedLatest) clientPackDTO {
	pack := clientPackDTO{
		ID:      e.id(),
		Network: e.Network,
		Env:     e.Env,
		Pin:     e.pin(),
		Status:  "no-source",
		Notes:   firstNonEmpty(e.SkipReason),
		Entry:   e,
		Files:   []clientFileDTO{},
	}
	if cached.Error != "" && cached.Version == "" && cached.Tag == "" {
		pack.Status = "error"
		pack.Error = cached.Error
		return pack
	}
	latest := ghLatest{Version: cached.Version, Tag: cached.Tag}
	pack.Latest = firstNonEmpty(latest.Version, latest.Tag)
	pack.Tag = latest.Tag
	pack.Error = cached.Error
	switch {
	case pack.Latest == "":
		pack.Status = "unknown"
	case pack.Pin != "" && sameVersion(pack.Latest, pack.Pin):
		pack.Status = "ok"
	case pack.Pin == "":
		pack.Status = "new"
	default:
		pack.Status = "update"
	}
	ver := firstNonEmpty(pack.Latest, pack.Pin)
	jobs := e.downloadJobs(latest)
	for _, j := range jobs {
		dto := clientFileDTO{
			Role:     j.role,
			Arch:     j.arch,
			Name:     j.name,
			Upstream: j.url,
			URL:      j.url,
		}
		if rel, n, ok := w.fileOnDisk(e, ver, j); ok {
			dto.Ready = true
			dto.Bytes = n
			dto.Rel = rel
			dto.URL = "/files/" + rel
		}
		pack.Files = append(pack.Files, dto)
	}
	return pack
}

func (w *watcher) fileOnDisk(e catalogEntry, ver string, j downloadJob) (rel string, bytes int64, ok bool) {
	candidates := []string{
		filepath.ToSlash(filepath.Join("_updates", e.Network, e.Env, ver, jobRelPath(j))),
		filepath.ToSlash(filepath.Join(e.Network, e.Env, jobRelPath(j))),
	}
	if j.role != "config" {
		candidates = append(candidates,
			filepath.ToSlash(filepath.Join("_updates", e.Network, e.Env, ver, "dist", j.name)),
			filepath.ToSlash(filepath.Join(e.Network, e.Env, "dist", j.name)),
		)
	}
	root := filepath.Clean(w.clients)
	for _, rel := range candidates {
		full := filepath.Join(root, filepath.FromSlash(rel))
		fi, err := os.Stat(full)
		if err == nil && fi.Size() > 0 {
			return rel, fi.Size(), true
		}
	}
	return "", 0, false
}
