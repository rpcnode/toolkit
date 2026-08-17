package main

import (
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"time"
)

func (w *watcher) serveHTTP() error {
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(rw http.ResponseWriter, _ *http.Request) {
		writeJSON(rw, http.StatusOK, map[string]any{"ok": true, "service": "client-watch"})
	})
	mux.HandleFunc("/api/v1/status", w.withAuth(w.handleStatus))
	mux.HandleFunc("/api/v1/telegram", w.withAuth(w.handleTelegram))
	mux.HandleFunc("/api/v1/check", w.withAuth(w.handleCheck))
	mux.HandleFunc("/api/v1/versions", w.withAuth(w.handleVersions))
	srv := &http.Server{
		Addr:              w.listen,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.Printf("listen %s", w.listen)
	return srv.ListenAndServe()
}

func (w *watcher) withAuth(next http.HandlerFunc) http.HandlerFunc {
	return func(rw http.ResponseWriter, r *http.Request) {
		if w.apiToken != "" {
			got := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
			if got != w.apiToken {
				writeJSON(rw, http.StatusUnauthorized, map[string]any{"ok": false, "error": "unauthorized"})
				return
			}
		}
		next(rw, r)
	}
}

func (w *watcher) handleStatus(rw http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		rw.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	st := w.state.snapshot()
	writeJSON(rw, http.StatusOK, map[string]any{
		"ok":          true,
		"telegram":    strings.TrimSpace(st.TelegramToken) != "" && strings.TrimSpace(st.TelegramChat) != "",
		"chat":        st.TelegramChat,
		"last_check":  st.LastCheck,
		"last_error":  st.LastError,
		"interval":    w.interval.String(),
		"catalog":     w.catalog,
		"seen":        st.Seen,
		"public_base": w.publicBase,
	})
}

func (w *watcher) handleTelegram(rw http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPut && r.Method != http.MethodPost {
		rw.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	var req struct {
		Token string `json:"token"`
		Chat  string `json:"chat"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(rw, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	req.Token = strings.TrimSpace(req.Token)
	req.Chat = strings.TrimSpace(req.Chat)
	if req.Token == "" || req.Chat == "" {
		writeJSON(rw, http.StatusBadRequest, map[string]any{"ok": false, "error": "нужны token и chat"})
		return
	}
	if err := w.state.setTelegram(req.Token, req.Chat); err != nil {
		writeJSON(rw, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	if err := sendTelegram(req.Token, req.Chat, "Telegram подключён. Сюда придёт: сеть — вышла новая версия."); err != nil {
		writeJSON(rw, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(rw, http.StatusOK, map[string]any{"ok": true, "chat": req.Chat})
}

func (w *watcher) handleVersions(rw http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		rw.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	rows, err := w.listVersions()
	if err != nil {
		writeJSON(rw, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
		return
	}
	writeJSON(rw, http.StatusOK, map[string]any{"ok": true, "entries": rows})
}

func (w *watcher) handleCheck(rw http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		rw.WriteHeader(http.StatusMethodNotAllowed)
		return
	}
	go func() {
		if err := w.checkOnce(); err != nil {
			log.Printf("check: %v", err)
		}
	}()
	writeJSON(rw, http.StatusAccepted, map[string]any{"ok": true, "started": true})
}

func writeJSON(rw http.ResponseWriter, code int, v any) {
	rw.Header().Set("Content-Type", "application/json")
	rw.WriteHeader(code)
	_ = json.NewEncoder(rw).Encode(v)
}
