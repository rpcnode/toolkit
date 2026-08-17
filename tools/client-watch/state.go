package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type watchState struct {
	TelegramToken string                `json:"telegram_token,omitempty"`
	TelegramChat  string                `json:"telegram_chat,omitempty"`
	Seen          map[string]seenUpdate `json:"seen,omitempty"`
	LastCheck     string                `json:"last_check,omitempty"`
	LastError     string                `json:"last_error,omitempty"`
}

type seenUpdate struct {
	Tag        string `json:"tag"`
	Version    string `json:"version"`
	NotifiedAt string `json:"notified_at"`
	URL        string `json:"url,omitempty"`
}

type stateStore struct {
	path string
	mu   sync.Mutex
	cur  watchState
}

func loadState(path string) (*stateStore, error) {
	s := &stateStore{path: path, cur: watchState{Seen: map[string]seenUpdate{}}}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return s, nil
		}
		return nil, err
	}
	if err := json.Unmarshal(data, &s.cur); err != nil {
		return nil, err
	}
	if s.cur.Seen == nil {
		s.cur.Seen = map[string]seenUpdate{}
	}
	return s, nil
}

func (s *stateStore) snapshot() watchState {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := s.cur
	if out.Seen != nil {
		cp := make(map[string]seenUpdate, len(out.Seen))
		for k, v := range out.Seen {
			cp[k] = v
		}
		out.Seen = cp
	}
	return out
}

func (s *stateStore) setTelegram(token, chat string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cur.TelegramToken = token
	s.cur.TelegramChat = chat
	return s.writeLocked()
}

func (s *stateStore) markCheck(err error) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.cur.LastCheck = time.Now().UTC().Format(time.RFC3339)
	if err != nil {
		s.cur.LastError = err.Error()
	} else {
		s.cur.LastError = ""
	}
	return s.writeLocked()
}

func (s *stateStore) markSeen(id string, latest ghLatest, public string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.cur.Seen == nil {
		s.cur.Seen = map[string]seenUpdate{}
	}
	s.cur.Seen[id] = seenUpdate{
		Tag:        latest.Tag,
		Version:    latest.Version,
		NotifiedAt: time.Now().UTC().Format(time.RFC3339),
		URL:        public,
	}
	return s.writeLocked()
}

func (s *stateStore) writeLocked() error {
	if err := os.MkdirAll(filepath.Dir(s.path), 0o755); err != nil {
		return err
	}
	data, err := json.MarshalIndent(s.cur, "", "  ")
	if err != nil {
		return err
	}
	tmp := s.path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, s.path)
}
