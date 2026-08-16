package main

import (
	"encoding/base64"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// bitcoinCookieRelPath — datadir-relative .cookie (aligned with system-agent profiles).
func bitcoinCookieRelPath(env string) string {
	switch strings.ToLower(strings.TrimSpace(env)) {
	case "testnet4":
		return "testnet4/.cookie"
	case "signet":
		return "signet/.cookie"
	case "regtest":
		return "regtest/.cookie"
	default:
		return ".cookie"
	}
}

func resolveBitcoinCookiePath(cfg Config) string {
	if p := envOr("BITCOIN_COOKIE", envOr("TRON_COOKIE", "")); p != "" {
		return p
	}
	data := envOr("TRON_DATA", "")
	if data == "" {
		prof := lookupPortProfile(envOr("TRON_NETWORK", ""), cfg.Env)
		data = prof.DataPath
	}
	if data == "" {
		return ""
	}

	// DataPath / TRON_DATA is the chain directory (/data/bitcoin/regtest).
	// Core nests under parent datadir — cookie is always <chain>/.cookie.
	return filepath.Join(data, ".cookie")
}

func readBitcoinCookieFile(path string) (user, pass string, ok bool) {
	b, err := os.ReadFile(path)
	if err != nil {
		return "", "", false
	}
	s := strings.TrimSpace(string(b))
	parts := strings.SplitN(s, ":", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return "", "", false
	}

	return parts[0], parts[1], true
}

// upstreamRPCAuthHeader — Basic auth for bitcoind. Prefer BITCOIN_RPC_USER/PASSWORD, else cookie.
// Public Go RPC stays open to clients; this header is injected only toward localhost upstream.
func upstreamRPCAuthHeader(cfg Config) string {
	if u := envOr("BITCOIN_RPC_USER", ""); u != "" {
		p := envOr("BITCOIN_RPC_PASSWORD", "")
		return "Basic " + base64.StdEncoding.EncodeToString([]byte(u+":"+p))
	}
	path := cfg.CookieFile
	if path == "" {
		path = resolveBitcoinCookiePath(cfg)
	}
	user, pass, ok := readBitcoinCookieFile(path)
	if !ok {
		return ""
	}

	return "Basic " + base64.StdEncoding.EncodeToString([]byte(user+":"+pass))
}

func networkIsBitcoin(cfg Config) bool {
	n := strings.ToLower(strings.TrimSpace(envOr("TRON_NETWORK", "")))
	if n == "bitcoin" {
		return true
	}
	// Fallback: upstream on bitcoin profile ports.
	return cfg.UpstreamPort == 8332 || cfg.UpstreamPort == 18332 ||
		cfg.UpstreamPort == 38332 || cfg.UpstreamPort == 18443
}

func fmtCookieHint(cfg Config) string {
	p := cfg.CookieFile
	if p == "" {
		p = resolveBitcoinCookiePath(cfg)
	}

	return fmt.Sprintf("cookie=%s", p)
}
