package main

import (
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/ali3/tron-toolkit/panel/store"
)

const (
	metaNotificationsTelegram = "notifications_telegram"
	notifyTestTTL             = 10 * time.Minute
	telegramAPIBase           = "https://api.telegram.org"
)

// Subscription event keys (panel → Telegram).
const (
	subClientUpdate   = "client.update_available"
	subLifecycleStep  = "lifecycle.step"
	subLifecycleError = "lifecycle.error"
	subLifecycleReady = "lifecycle.ready"
	subNodeDown       = "node.down"
	subNodeUp         = "node.up"
	subAgentUpdate    = "agent.update_available"
	subDiskLow        = "disk.low"
	subCPUHigh        = "cpu.high"
	subRPCSlow        = "rpc.slow"
	subRPCErrors      = "rpc.errors"
	subRPCRPSHigh     = "rpc.rps_high"
)

const (
	defaultDiskUsedPct     = 90.0
	defaultCPUHighPct      = 90.0
	defaultRPCLatencyP95Ms = 2000.0
	defaultRPCErrorRatePct = 10.0
	defaultRPCRPS          = 1000.0 // fullnode Go proxy rps_1m
	minRPCErrorSample      = 20     // requests between polls before error-rate alert
	// node.down / node.up — continuous hold before Telegram (collector ~2s tick).
	defaultNodeDownHoldSec = 45.0
	defaultNodeUpHoldSec   = 20.0
	defaultNodeDownHold    = 45 * time.Second
	defaultNodeUpHold      = 20 * time.Second
)

var defaultNotifySubscriptions = map[string]bool{
	subClientUpdate:   true,
	subLifecycleStep:  true,
	subLifecycleError: true,
	subLifecycleReady: true,
	subNodeDown:       true,
	subNodeUp:         true,
	subAgentUpdate:    true,
	subDiskLow:        true,
	subCPUHigh:        true,
	subRPCSlow:        true,
	subRPCErrors:      true,
	subRPCRPSHigh:     true,
}

type notifyThresholds struct {
	DiskUsedPct     float64 `json:"disk_used_pct"`
	CPUHighPct      float64 `json:"cpu_high_pct"`
	RPCLatencyP95Ms float64 `json:"rpc_latency_p95_ms"`
	RPCErrorRatePct float64 `json:"rpc_error_rate_pct"`
	RPCRPS          float64 `json:"rpc_rps"` // fullnode Go proxy requests/sec (rps_1m)
	// Seconds of continuous unreachable before node.down / healthy before node.up.
	NodeDownHoldSec float64 `json:"node_down_hold_sec"`
	NodeUpHoldSec   float64 `json:"node_up_hold_sec"`
}

func defaultNotifyThresholds() notifyThresholds {
	return notifyThresholds{
		DiskUsedPct:     defaultDiskUsedPct,
		CPUHighPct:      defaultCPUHighPct,
		RPCLatencyP95Ms: defaultRPCLatencyP95Ms,
		RPCErrorRatePct: defaultRPCErrorRatePct,
		RPCRPS:          defaultRPCRPS,
		NodeDownHoldSec: defaultNodeDownHoldSec,
		NodeUpHoldSec:   defaultNodeUpHoldSec,
	}
}

func mergeThresholds(in *notifyThresholds) notifyThresholds {
	out := defaultNotifyThresholds()
	if in == nil {
		return out
	}
	if in.DiskUsedPct > 0 {
		out.DiskUsedPct = in.DiskUsedPct
	}
	if in.CPUHighPct > 0 {
		out.CPUHighPct = in.CPUHighPct
	}
	if in.RPCLatencyP95Ms > 0 {
		out.RPCLatencyP95Ms = in.RPCLatencyP95Ms
	}
	if in.RPCErrorRatePct > 0 {
		out.RPCErrorRatePct = in.RPCErrorRatePct
	}
	if in.RPCRPS > 0 {
		out.RPCRPS = in.RPCRPS
	}
	if in.NodeDownHoldSec > 0 {
		out.NodeDownHoldSec = in.NodeDownHoldSec
	}
	if in.NodeUpHoldSec > 0 {
		out.NodeUpHoldSec = in.NodeUpHoldSec
	}
	return out
}

func (th notifyThresholds) nodeDownHold() time.Duration {
	if th.NodeDownHoldSec > 0 {
		return time.Duration(th.NodeDownHoldSec * float64(time.Second))
	}
	return defaultNodeDownHold
}

func (th notifyThresholds) nodeUpHold() time.Duration {
	if th.NodeUpHoldSec > 0 {
		return time.Duration(th.NodeUpHoldSec * float64(time.Second))
	}
	return defaultNodeUpHold
}

type telegramNotifySettings struct {
	BotTokenEnc     string           `json:"bot_token_enc,omitempty"`
	TokenHint       string           `json:"token_hint,omitempty"`
	ChatID          string           `json:"chat_id,omitempty"`
	Enabled         bool             `json:"enabled"`
	Subscriptions   map[string]bool  `json:"subscriptions,omitempty"`
	Thresholds      notifyThresholds `json:"thresholds"`
	VerifiedAt      string           `json:"verified_at,omitempty"`
	TestCodeHash    string           `json:"test_code_hash,omitempty"`
	TestCodeExpires string           `json:"test_code_expires,omitempty"`
	LastError       string           `json:"last_error,omitempty"`
}

type telegramNotifyPublic struct {
	OK                  bool             `json:"ok"`
	Enabled             bool             `json:"enabled"`
	ChatID              string           `json:"chat_id"`
	HasToken            bool             `json:"has_token"`
	TokenHint           string           `json:"token_hint,omitempty"`
	TokenMasked         string           `json:"token_masked,omitempty"`
	TokenDecryptOK      bool             `json:"token_decrypt_ok"`
	Verified            bool             `json:"verified"`
	VerifiedAt          string           `json:"verified_at,omitempty"`
	Subscriptions       map[string]bool  `json:"subscriptions"`
	Thresholds          notifyThresholds `json:"thresholds"`
	LastError           string           `json:"last_error,omitempty"`
	KeySource           string           `json:"key_source,omitempty"`
	KeyPath             string           `json:"key_path,omitempty"`
	SubscriptionCatalog []notifySubInfo  `json:"subscription_catalog"`
}

type notifySubInfo struct {
	Key         string `json:"key"`
	Label       string `json:"label"`
	Description string `json:"description"`
}

var notifySubCatalog = []notifySubInfo{
	{subClientUpdate, "Client update available", "New chain client_version on CDN / release channel"},
	{subLifecycleStep, "Install / lifecycle steps", "Phase changes: install → snapshot → start → syncing"},
	{subLifecycleError, "Lifecycle error", "Node entered error phase"},
	{subLifecycleReady, "Node ready", "Node reached working / healthy"},
	{subNodeDown, "Node down", "Agent unreachable or RPC unhealthy for hold duration (default 45s)"},
	{subNodeUp, "Node up", "Recovered after down (default 20s healthy hold)"},
	{subAgentUpdate, "Agent update available", "Host toolkit/agent behind CDN"},
	{subDiskLow, "Disk low", "Host disk used % above threshold (default 90%)"},
	{subCPUHigh, "CPU high", "Host CPU busy % above threshold (default 90%; load is not used)"},
	{subRPCSlow, "Fullnode RPC slow", "Go proxy latency p95 above threshold (default 2000 ms)"},
	{subRPCErrors, "Fullnode RPC errors", "Go proxy 5xx/upstream error rate above threshold (default 10%)"},
	{subRPCRPSHigh, "Fullnode RPC RPS high", "Go proxy requests/sec (rps_1m) above threshold (default 1000)"},
}

var (
	notifySendMu sync.Mutex
	notifyHTTP   = &http.Client{Timeout: 12 * time.Second}
)

func mergeSubscriptions(in map[string]bool) map[string]bool {
	out := map[string]bool{}
	for k, v := range defaultNotifySubscriptions {
		out[k] = v
	}
	for k, v := range in {
		k = strings.TrimSpace(k)
		if k == "" {
			continue
		}
		if _, ok := defaultNotifySubscriptions[k]; ok {
			out[k] = v
		}
	}
	return out
}

func loadTelegramNotifySettings(db *store.DB) (telegramNotifySettings, error) {
	var st telegramNotifySettings
	raw, ok, err := db.GetMeta(metaNotificationsTelegram)
	if err != nil {
		return st, err
	}
	if ok && strings.TrimSpace(raw) != "" {
		_ = json.Unmarshal([]byte(raw), &st)
	}
	st.Subscriptions = mergeSubscriptions(st.Subscriptions)
	st.Thresholds = mergeThresholds(&st.Thresholds)
	return st, nil
}

func saveTelegramNotifySettings(db *store.DB, st telegramNotifySettings) error {
	st.Subscriptions = mergeSubscriptions(st.Subscriptions)
	st.Thresholds = mergeThresholds(&st.Thresholds)
	b, err := json.MarshalIndent(st, "", "  ")
	if err != nil {
		return err
	}
	return db.SetMeta(metaNotificationsTelegram, string(b)+"\n")
}

func (s *Server) notifyPublicView(st telegramNotifySettings) telegramNotifyPublic {
	_, keySrc, keyErr := loadOrCreateNotifyKey(s.cfg.DBPath)
	decryptOK := false
	if st.BotTokenEnc != "" {
		if _, err := decryptNotifySecret(s.cfg.DBPath, st.BotTokenEnc); err == nil {
			decryptOK = true
		}
	}
	hint := st.TokenHint
	masked := ""
	if hint != "" {
		masked = "••••" + hint
	}
	keyPath := ""
	if keySrc == "file" {
		keyPath = notifyKeyPath(s.cfg.DBPath)
	}
	if keyErr != nil {
		st.LastError = "notify key: " + keyErr.Error()
	}
	return telegramNotifyPublic{
		OK:                  true,
		Enabled:             st.Enabled,
		ChatID:              st.ChatID,
		HasToken:            st.BotTokenEnc != "",
		TokenHint:           hint,
		TokenMasked:         masked,
		TokenDecryptOK:      decryptOK,
		Verified:            strings.TrimSpace(st.VerifiedAt) != "",
		VerifiedAt:          st.VerifiedAt,
		Subscriptions:       mergeSubscriptions(st.Subscriptions),
		Thresholds:          mergeThresholds(&st.Thresholds),
		LastError:           st.LastError,
		KeySource:           keySrc,
		KeyPath:             keyPath,
		SubscriptionCatalog: notifySubCatalog,
	}
}

func (s *Server) resolveBotToken(st telegramNotifySettings) (string, error) {
	if strings.TrimSpace(st.BotTokenEnc) == "" {
		return "", fmt.Errorf("bot token not configured")
	}
	tok, err := decryptNotifySecret(s.cfg.DBPath, st.BotTokenEnc)
	if err != nil {
		return "", fmt.Errorf("cannot decrypt bot token (re-enter token / check panel.notify.key): %w", err)
	}
	tok = strings.TrimSpace(tok)
	if tok == "" {
		return "", fmt.Errorf("bot token empty after decrypt")
	}
	return tok, nil
}

func sendTelegramMessage(token, chatID, text string) error {
	token = strings.TrimSpace(token)
	chatID = strings.TrimSpace(chatID)
	text = strings.TrimSpace(text)
	if token == "" || chatID == "" || text == "" {
		return fmt.Errorf("token, chat_id and text required")
	}
	body, _ := json.Marshal(map[string]any{
		"chat_id":                  chatID,
		"text":                     text,
		"disable_web_page_preview": true,
	})
	url := telegramAPIBase + "/bot" + token + "/sendMessage"
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(body))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := notifyHTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	var tg struct {
		OK          bool   `json:"ok"`
		Description string `json:"description"`
	}
	_ = json.Unmarshal(raw, &tg)
	if resp.StatusCode >= 300 || !tg.OK {
		msg := strings.TrimSpace(tg.Description)
		if msg == "" {
			msg = string(raw)
		}
		if msg == "" {
			msg = fmt.Sprintf("HTTP %d", resp.StatusCode)
		}
		return fmt.Errorf("telegram: %s", msg)
	}
	return nil
}

func hashNotifyTestCode(code string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(code)))
	return hex.EncodeToString(sum[:])
}

func genNotifyTestCode() (string, error) {
	var b [4]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", err
	}
	n := int(b[0])<<16 | int(b[1])<<8 | int(b[2])
	n = n % 1000000
	return fmt.Sprintf("%06d", n), nil
}

func (s *Server) handleNotificationsAPI(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimSuffix(r.URL.Path, "/")
	switch {
	case path == "/api/notifications/settings" && r.Method == http.MethodGet:
		st, err := loadTelegramNotifySettings(s.db)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, s.notifyPublicView(st))

	case path == "/api/notifications/settings" && r.Method == http.MethodPut:
		var body struct {
			BotToken      *string           `json:"bot_token"`
			ChatID        *string           `json:"chat_id"`
			Enabled       *bool             `json:"enabled"`
			Subscriptions map[string]bool   `json:"subscriptions"`
			Thresholds    *notifyThresholds `json:"thresholds"`
			ClearToken    bool              `json:"clear_token"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
			return
		}
		st, err := loadTelegramNotifySettings(s.db)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		if body.ClearToken {
			st.BotTokenEnc = ""
			st.TokenHint = ""
			st.VerifiedAt = ""
		}
		if body.BotToken != nil {
			tok := strings.TrimSpace(*body.BotToken)
			if tok != "" {
				enc, encErr := encryptNotifySecret(s.cfg.DBPath, tok)
				if encErr != nil {
					writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": encErr.Error()})
					return
				}
				st.BotTokenEnc = enc
				st.TokenHint = tokenHint(tok)
				st.VerifiedAt = "" // must re-verify after token change
			}
		}
		if body.ChatID != nil {
			newChat := strings.TrimSpace(*body.ChatID)
			if newChat != st.ChatID {
				st.VerifiedAt = ""
			}
			st.ChatID = newChat
		}
		if body.Enabled != nil {
			st.Enabled = *body.Enabled
		}
		if body.Subscriptions != nil {
			st.Subscriptions = mergeSubscriptions(body.Subscriptions)
		}
		if body.Thresholds != nil {
			st.Thresholds = mergeThresholds(body.Thresholds)
		}
		st.LastError = ""
		if err := saveTelegramNotifySettings(s.db, st); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, s.notifyPublicView(st))

	case path == "/api/notifications/test" && r.Method == http.MethodPost:
		st, err := loadTelegramNotifySettings(s.db)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		tok, err := s.resolveBotToken(st)
		if err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		if strings.TrimSpace(st.ChatID) == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "chat_id required"})
			return
		}
		code, err := genNotifyTestCode()
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		msg := fmt.Sprintf("RpcNode panel test code: %s\nEnter this code in Notifications → Verify.", code)
		if err := sendTelegramMessage(tok, st.ChatID, msg); err != nil {
			st.LastError = err.Error()
			_ = saveTelegramNotifySettings(s.db, st)
			writeJSON(w, http.StatusBadGateway, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		st.TestCodeHash = hashNotifyTestCode(code)
		st.TestCodeExpires = time.Now().UTC().Add(notifyTestTTL).Format(time.RFC3339)
		st.LastError = ""
		if err := saveTelegramNotifySettings(s.db, st); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{
			"ok":         true,
			"sent":       true,
			"expires_at": st.TestCodeExpires,
			"message":    "Test code sent to Telegram. Enter it below to verify.",
		})

	case path == "/api/notifications/verify" && r.Method == http.MethodPost:
		var body struct {
			Code string `json:"code"`
		}
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid_json"})
			return
		}
		st, err := loadTelegramNotifySettings(s.db)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		code := strings.TrimSpace(body.Code)
		if code == "" || st.TestCodeHash == "" {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "no pending test — send Test first"})
			return
		}
		if exp, err := time.Parse(time.RFC3339, st.TestCodeExpires); err != nil || time.Now().UTC().After(exp) {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "test code expired — send Test again"})
			return
		}
		got := hashNotifyTestCode(code)
		if subtle.ConstantTimeCompare([]byte(got), []byte(st.TestCodeHash)) != 1 {
			writeJSON(w, http.StatusBadRequest, map[string]any{"ok": false, "error": "invalid code"})
			return
		}
		st.VerifiedAt = time.Now().UTC().Format(time.RFC3339)
		st.TestCodeHash = ""
		st.TestCodeExpires = ""
		st.LastError = ""
		st.Enabled = true
		if err := saveTelegramNotifySettings(s.db, st); err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"ok": false, "error": err.Error()})
			return
		}
		// Confirmation ping (best-effort).
		if tok, err := s.resolveBotToken(st); err == nil {
			_ = sendTelegramMessage(tok, st.ChatID, "RpcNode Notifications: channel verified. Alerts enabled.")
		}
		writeJSON(w, http.StatusOK, s.notifyPublicView(st))

	default:
		writeJSON(w, http.StatusNotFound, map[string]any{"ok": false, "error": "not_found"})
	}
}

// panelNotifySend sends a Telegram alert if notifications are enabled, verified, and subscribed.
func panelNotifySend(db *store.DB, dbPath, eventType, message string) {
	st, err := loadTelegramNotifySettings(db)
	if err != nil || !st.Enabled || strings.TrimSpace(st.VerifiedAt) == "" {
		return
	}
	subs := mergeSubscriptions(st.Subscriptions)
	if !subs[eventType] {
		return
	}
	tok, err := decryptNotifySecret(dbPath, st.BotTokenEnc)
	if err != nil || strings.TrimSpace(tok) == "" || strings.TrimSpace(st.ChatID) == "" {
		return
	}
	// message is already formatted by formatNotifyAlert (includes event + identity).
	text := strings.TrimSpace(message)
	if text == "" {
		return
	}
	notifySendMu.Lock()
	defer notifySendMu.Unlock()
	if err := sendTelegramMessage(tok, st.ChatID, text); err != nil {
		st.LastError = err.Error()
		_ = saveTelegramNotifySettings(db, st)
		log.Printf("notify telegram %s: %v", eventType, err)
		return
	}
	if st.LastError != "" {
		st.LastError = ""
		_ = saveTelegramNotifySettings(db, st)
	}
}

func formatDiskPct(v float64) string {
	return strconv.FormatFloat(v, 'f', 0, 64)
}
