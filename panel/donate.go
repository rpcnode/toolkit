package main

import (
	"encoding/json"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// Donate wallets live on the install CDN (same host as agent.sh) so ops can
// add network addresses without rebuilding the panel UI.
//   https://rpcnode.dev/install/donate.json

type donateWallet struct {
	Network string `json:"network"`
	Label   string `json:"label,omitempty"`
	Address string `json:"address"`
	Note    string `json:"note,omitempty"`
}

type donateDoc struct {
	OK        bool           `json:"ok"`
	UpdatedAt string         `json:"updated_at,omitempty"`
	Title     string         `json:"title,omitempty"`
	Blurb     string         `json:"blurb,omitempty"`
	Footer    string         `json:"footer,omitempty"`
	Wallets   []donateWallet `json:"wallets"`
	Source    string         `json:"source,omitempty"`
	CachedAt  string         `json:"cached_at,omitempty"`
}

var (
	donateMu     sync.Mutex
	donateCached *donateDoc
	donateAt     time.Time
)

func donateURL() string {
	if u := strings.TrimSpace(os.Getenv("DONATE_JSON_URL")); u != "" {
		return u
	}
	return installBaseURL() + "/donate.json"
}

func getDonateDoc(force bool) *donateDoc {
	donateMu.Lock()
	defer donateMu.Unlock()
	if !force && donateCached != nil && time.Since(donateAt) < 2*time.Minute {
		return donateCached
	}
	client := &http.Client{Timeout: 8 * time.Second}
	req, err := http.NewRequest(http.MethodGet, donateURL(), nil)
	if err != nil {
		return donateCached
	}
	resp, err := client.Do(req)
	if err != nil {
		return donateCached
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return donateCached
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 1<<20))
	if err != nil {
		return donateCached
	}
	var parsed struct {
		UpdatedAt string         `json:"updated_at"`
		Title     string         `json:"title"`
		Blurb     string         `json:"blurb"`
		Footer    string         `json:"footer"`
		Wallets   []donateWallet `json:"wallets"`
	}
	if err := json.Unmarshal(raw, &parsed); err != nil {
		return donateCached
	}
	wallets := make([]donateWallet, 0, len(parsed.Wallets))
	for _, w := range parsed.Wallets {
		addr := strings.TrimSpace(w.Address)
		net := strings.TrimSpace(w.Network)
		if addr == "" || net == "" {
			continue
		}
		wallets = append(wallets, donateWallet{
			Network: net,
			Label:   strings.TrimSpace(w.Label),
			Address: addr,
			Note:    strings.TrimSpace(w.Note),
		})
	}
	if len(wallets) == 0 {
		return donateCached
	}
	doc := &donateDoc{
		OK:        true,
		UpdatedAt: strings.TrimSpace(parsed.UpdatedAt),
		Title:     strings.TrimSpace(parsed.Title),
		Blurb:     strings.TrimSpace(parsed.Blurb),
		Footer:    strings.TrimSpace(parsed.Footer),
		Wallets:   wallets,
		Source:    donateURL(),
		CachedAt:  time.Now().UTC().Format(time.RFC3339),
	}
	donateCached = doc
	donateAt = time.Now()
	return donateCached
}

// handleDonate — panel-owned CDN proxy for install/donate.json (not host agent).
func (s *Server) handleDonate(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	force := strings.EqualFold(r.URL.Query().Get("refresh"), "1") ||
		strings.EqualFold(r.URL.Query().Get("refresh"), "true")
	doc := getDonateDoc(force)
	if doc == nil || len(doc.Wallets) == 0 {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":      false,
			"error":   "donate_unavailable",
			"source":  donateURL(),
			"wallets": []any{},
			"message": "Could not load donate.json from install CDN",
		})
		return
	}
	writeJSON(w, http.StatusOK, doc)
}
