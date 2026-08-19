package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"
)

// ClientRelease — canonical download for a network/env client (install + check + update).
// Primary source is the vendored CDN catalog (RpcNode.app → frontend/toolkit/public/clients).
// GitHub / pin is fallback only. Env TRON_TAG / TRON_JAR_URL still wins.
type ClientRelease struct {
	Network        string `json:"network"`
	Env            string `json:"env"`
	Version        string `json:"version"` // comparable (e.g. 4.8.2.1)
	Tag            string `json:"tag,omitempty"`
	ArtifactURL    string `json:"artifact_url"`
	SHA256         string `json:"sha256,omitempty"`
	ConfURL        string `json:"conf_url,omitempty"`
	ArtifactKind   string `json:"artifact_kind"`
	NeedsConfPatch bool   `json:"needs_conf_patch"`
	Source         string `json:"source"` // cdn | github | pin | env
	Notes          string `json:"notes,omitempty"`
}

// Pinned java-tron — same default as lib/paths.sh TRON_TAG / provision.
const tronClientPinTag = "GreatVoyage-v4.8.2.1"

var (
	tronReleaseCacheMu   sync.Mutex
	tronReleaseCacheTag  string
	tronReleaseCacheAt   time.Time
	tronReleaseCacheTTL  = 1 * time.Hour
)

// ResolveClientRelease returns latest (or pinned) client artifact for network/env.
func ResolveClientRelease(network, env string) (ClientRelease, error) {
	net := strings.ToLower(strings.TrimSpace(network))
	if net == "" {
		net = "tron"
	}
	env = strings.ToLower(strings.TrimSpace(env))
	if env == "" {
		env = "mainnet"
	}
	if net == "tron" {
		if rel, ok := tronEnvOverride(env); ok {
			return rel, nil
		}
	}
	if rel, err := fetchVendoredClientRelease(net, env); err == nil && strings.TrimSpace(rel.ArtifactURL) != "" {
		return rel, nil
	}
	switch net {
	case "tron":
		return resolveTronClientRelease(env)
	default:
		return ClientRelease{}, fmt.Errorf("no vendored catalog for %s/%s — publish clients/%s/%s/manifest.json", net, env, net, env)
	}
}

func tronEnvOverride(env string) (ClientRelease, bool) {
	tag := strings.TrimSpace(os.Getenv("TRON_TAG"))
	jar := strings.TrimSpace(os.Getenv("TRON_JAR_URL"))
	conf := strings.TrimSpace(os.Getenv("TRON_CONFIG_URL"))
	if tag == "" && jar == "" && conf == "" {
		return ClientRelease{}, false
	}
	if tag == "" {
		tag = tronClientPinTag
	}
	rel := tronReleaseFromTag(tag, env, "env")
	if jar != "" {
		rel.ArtifactURL = jar
	}
	if conf != "" {
		rel.ConfURL = conf
	}
	return rel, true
}

func resolveTronClientRelease(env string) (ClientRelease, error) {
	if rel, ok := tronEnvOverride(env); ok {
		return rel, nil
	}
	if strings.TrimSpace(os.Getenv("RPCNODE_CLIENT_RELEASE_PIN")) == "1" {
		return tronReleaseFromTag(tronClientPinTag, env, "pin"), nil
	}
	tag, err := fetchTronGithubLatestTag()
	if err != nil || tag == "" {
		rel := tronReleaseFromTag(tronClientPinTag, env, "pin")
		if err != nil {
			rel.Notes = "github latest failed (" + err.Error() + "); using pin " + tronClientPinTag
		}
		return rel, nil
	}
	return tronReleaseFromTag(tag, env, "github"), nil
}

func tronReleaseFromTag(tag, env, source string) ClientRelease {
	tag = strings.TrimSpace(tag)
	ver := normalizeClientVersion(tag)
	jar := fmt.Sprintf("https://github.com/tronprotocol/java-tron/releases/download/%s/FullNode.jar", tag)
	conf := fmt.Sprintf("https://raw.githubusercontent.com/tronprotocol/java-tron/%s/framework/src/main/resources/config.conf", tag)
	switch strings.ToLower(strings.TrimSpace(env)) {
	case "nile":
		// Fallback only. Product path is vendored CDN (PQ jar + config-nile.conf).
		jar = "https://github.com/tron-nile-testnet/nile-testnet/releases/download/GreatVoyage-Nile-v4.8.2.1-PQ1-build1/FullNode-Nile-x64-4.8.2.1-pq1-build1.jar"
		if hostIsARM() {
			jar = "https://github.com/tron-nile-testnet/nile-testnet/releases/download/GreatVoyage-Nile-v4.8.2.1-PQ1-build1/FullNode-Nile-aarch64-4.8.2.1-pq1-build1.jar"
		}
		conf = "https://raw.githubusercontent.com/tron-nile-testnet/nile-testnet/master/framework/src/main/resources/config-nile.conf"
	}
	return ClientRelease{
		Network:        "tron",
		Env:            env,
		Version:        ver,
		Tag:            tag,
		ArtifactURL:    jar,
		ConfURL:        conf,
		ArtifactKind:   "jar",
		NeedsConfPatch: true,
		Source:         source,
		Notes:          "java-tron FullNode (" + tag + ")",
	}
}

func fetchTronGithubLatestTag() (string, error) {
	tronReleaseCacheMu.Lock()
	if tronReleaseCacheTag != "" && time.Since(tronReleaseCacheAt) < tronReleaseCacheTTL {
		tag := tronReleaseCacheTag
		tronReleaseCacheMu.Unlock()
		return tag, nil
	}
	tronReleaseCacheMu.Unlock()

	client := &http.Client{Timeout: 15 * time.Second}
	req, err := http.NewRequest(http.MethodGet, "https://api.github.com/repos/tronprotocol/java-tron/releases?per_page=30", nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "rpcnode-system-agent")
	if tok := strings.TrimSpace(os.Getenv("GITHUB_TOKEN")); tok != "" {
		req.Header.Set("Authorization", "Bearer "+tok)
	}
	resp, err := client.Do(req)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		b, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<10))
		return "", fmt.Errorf("GitHub HTTP %d: %s", resp.StatusCode, strings.TrimSpace(string(b)))
	}
	var releases []struct {
		TagName    string `json:"tag_name"`
		Draft      bool   `json:"draft"`
		Prerelease bool   `json:"prerelease"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 2<<20)).Decode(&releases); err != nil {
		return "", err
	}
	tag := ""
	for _, r := range releases {
		if r.Draft || r.Prerelease {
			continue
		}
		t := strings.TrimSpace(r.TagName)
		if strings.HasPrefix(t, "GreatVoyage-v") || strings.HasPrefix(t, "GreatVoyage-V") {
			tag = t
			break
		}
	}
	if tag == "" {
		return "", fmt.Errorf("no GreatVoyage release in GitHub listing")
	}
	tronReleaseCacheMu.Lock()
	tronReleaseCacheTag = tag
	tronReleaseCacheAt = time.Now()
	tronReleaseCacheMu.Unlock()
	return tag, nil
}

// formatClientVersion — canonical client_version for status.json (all networks).
// Lowercase, no slashes — agent normalizes once; UI only renders.
//
//	"/Dash Core:23.1.8/"                 → "dash core 23.1.8"
//	"/Satoshi:27.0.0/"                   → "satoshi 27.0.0"
//	"/Bitcoin Cash Node:29.1.0(EB32.0)/" → "29.1.0(eb32.0)"  (network name stripped)
//	"Geth/v1.14.0/linux-…"               → "geth 1.14.0"
//	"GreatVoyage-v4.8.2.1"               → "4.8.2.1"
func formatClientVersion(s string) string {
	s = strings.TrimSpace(s)
	if s == "" {
		return ""
	}
	// Never treat shell / PATH noise as a client version (TON validator-engine missing).
	low := strings.ToLower(s)
	if strings.Contains(low, "command not found") ||
		strings.HasPrefix(low, "bash:") ||
		strings.HasPrefix(low, "sh:") ||
		strings.Contains(low, "no such file") {
		return ""
	}
	// Dual-client (ethereum): "geth 1.17.4 · lighthouse 8.2.1" — format each side.
	if strings.Contains(s, "·") {
		var parts []string
		for _, p := range strings.Split(s, "·") {
			if f := formatClientVersion(strings.TrimSpace(p)); f != "" {
				parts = append(parts, f)
			}
		}
		return strings.Join(parts, " · ")
	}
	// Bitcoin Core / Dash / LTC / BCH / Doge getnetworkinfo.subversion.
	for strings.HasPrefix(s, "/") || strings.HasSuffix(s, "/") {
		s = strings.TrimPrefix(s, "/")
		s = strings.TrimSuffix(s, "/")
		s = strings.TrimSpace(s)
	}
	out := ""
	// "Dash Core:23.1.8" → "dash core 23.1.8" (avoid URLs with ://).
	if i := strings.LastIndex(s, ":"); i > 0 && !strings.Contains(s[:i], "://") {
		name := strings.TrimSpace(s[:i])
		ver := strings.TrimSpace(s[i+1:])
		if name != "" && clientVersionToken(ver) {
			if clientVersionNameRedundant(name) {
				out = trimVersionV(ver)
			} else {
				out = name + " " + trimVersionV(ver)
			}
		}
	}
	// web3_clientVersion: "Geth/v1.14.0/linux-amd64/go1.22" → "geth 1.14.0"
	if out == "" && strings.Count(s, "/") >= 1 {
		parts := strings.Split(s, "/")
		head := strings.TrimSpace(parts[0])
		ver := strings.TrimSpace(parts[1])
		if head != "" && clientVersionToken(ver) {
			out = head + " " + trimVersionV(ver)
		}
	}
	// TRON release tags.
	if out == "" {
		low := strings.ToLower(s)
		for _, p := range []string{"greatvoyage-", "java-tron-", "tron-"} {
			if strings.HasPrefix(low, p) {
				out = trimVersionV(strings.TrimSpace(s[len(p):]))
				break
			}
		}
	}
	if out == "" {
		fields := strings.Fields(s)
		if len(fields) >= 2 {
			ver := fields[len(fields)-1]
			if clientVersionToken(ver) {
				name := strings.Join(fields[:len(fields)-1], " ")
				if clientVersionNameRedundant(name) {
					out = trimVersionV(ver)
				} else {
					out = name + " " + trimVersionV(ver)
				}
			}
		}
	}
	if out == "" {
		out = s
	}
	// Canonical: lowercase, no slashes, single spaces.
	out = strings.ToLower(out)
	out = strings.ReplaceAll(out, "/", " ")
	out = strings.Join(strings.Fields(out), " ")
	return out
}

// normalizeClientVersion — semver-ish token for local/latest compare.
// Display strings ("Dash Core 23.1.8") → "23.1.8"; TRON tags → "4.8.2.1".
func normalizeClientVersion(s string) string {
	s = formatClientVersion(s)
	if s == "" {
		return ""
	}
	fields := strings.Fields(s)
	if len(fields) >= 2 {
		last := fields[len(fields)-1]
		if clientVersionToken(last) {
			return trimVersionV(last)
		}
	}
	return trimVersionV(s)
}

func trimVersionV(s string) string {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "v")
	s = strings.TrimPrefix(s, "V")
	return strings.TrimSpace(s)
}

// clientVersionNameRedundant — product name already restates the network (BCH/LTC/…).
// Keep brand UA that is not the network label (Satoshi, Dash Core, Geth).
func clientVersionNameRedundant(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	n = strings.Join(strings.Fields(n), " ")
	switch n {
	case "bitcoin cash node", "bitcoin cash", "bchn",
		"litecoin core", "litecoin",
		"dogecoin core", "dogecoin",
		"bitcoin core":
		return true
	}
	return strings.Contains(n, "bitcoin cash")
}

// clientVersionToken — "23.1.8", "v1.14.0", "1.14.0-stable", "29.1.0(eb32.0)".
func clientVersionToken(s string) bool {
	s = trimVersionV(s)
	if s == "" {
		return false
	}
	// BCHN-style build id: 29.1.0(EB32.0)
	if i := strings.IndexByte(s, '('); i > 0 && strings.HasSuffix(s, ")") && i+1 < len(s)-1 {
		return clientVersionToken(s[:i])
	}
	dot := false
	digit := false
	for i, r := range s {
		if r >= '0' && r <= '9' {
			digit = true
			continue
		}
		if r == '.' {
			dot = true
			continue
		}
		if r == '-' || r == '_' || r == '+' {
			// pre-release / build suffix after digits
			if digit {
				return true
			}
			return false
		}
		if i == 0 {
			return false
		}
		// allow "1.14.0-stable"
		if digit && ((r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z')) {
			continue
		}
		return false
	}
	return digit && dot
}

// formatEthereumClientVersion — EL + CL for Nodes/detail. Catalog pin is Lighthouse.
func formatEthereumClientVersion(gethRaw, lighthouseRaw string) string {
	geth := formatClientVersion(gethRaw)
	lh := formatLighthouseClientVersion(lighthouseRaw)
	switch {
	case geth != "" && lh != "":
		return geth + " · " + lh
	case geth != "":
		return geth
	default:
		return lh
	}
}

func formatLighthouseClientVersion(raw string) string {
	if v := parseLighthouseVersion(raw); v != "" {
		return "lighthouse " + v
	}
	s := formatClientVersion(raw)
	if s == "" {
		return ""
	}
	if n := normalizeClientVersion(s); n != "" {
		return "lighthouse " + n
	}
	if strings.HasPrefix(s, "lighthouse") {
		return s
	}
	return "lighthouse " + s
}

// parseLighthouseVersion — "Lighthouse v8.2.1", "Lighthouse/v8.2.1-xxx", beacon JSON version.
func parseLighthouseVersion(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	if i := strings.IndexAny(raw, "\r\n"); i >= 0 {
		raw = raw[:i]
	}
	low := strings.ToLower(raw)
	const name = "lighthouse"
	if i := strings.Index(low, name); i >= 0 {
		rest := strings.TrimSpace(raw[i+len(name):])
		rest = strings.TrimPrefix(rest, "/")
		rest = strings.TrimSpace(rest)
		fields := strings.Fields(rest)
		if len(fields) > 0 && clientVersionToken(fields[0]) {
			return trimVersionV(fields[0])
		}
	}
	if n := normalizeClientVersion(raw); n != "" && clientVersionToken(n) {
		return n
	}
	return ""
}

func splitClientVersionParts(s string) []string {
	if strings.Contains(s, "·") {
		return strings.Split(s, "·")
	}
	return []string{s}
}

// extractNamedClientVersion — "geth 1.17.4 · lighthouse 8.2.1" + "lighthouse" → "8.2.1".
func extractNamedClientVersion(s, name string) string {
	s = strings.TrimSpace(s)
	name = strings.ToLower(strings.TrimSpace(name))
	if s == "" || name == "" {
		return ""
	}
	for _, part := range splitClientVersionParts(s) {
		fields := strings.Fields(strings.ToLower(strings.TrimSpace(part)))
		if len(fields) == 0 {
			continue
		}
		if fields[0] == name {
			return normalizeClientVersion(part)
		}
	}
	if strings.Contains(strings.ToLower(s), name) {
		return normalizeClientVersion(s)
	}
	return ""
}

// clientVersionsForUpdate — ethereum catalog pin is Lighthouse; never compare geth to 8.2.1.
func clientVersionsForUpdate(network, local, latest string) (localN, latestN, latestDisplay string) {
	latestN = normalizeClientVersion(latest)
	if strings.EqualFold(strings.TrimSpace(network), "ethereum") {
		if latestN != "" {
			latestDisplay = "lighthouse " + latestN
		}
		localN = extractNamedClientVersion(local, "lighthouse")
		return localN, latestN, latestDisplay
	}
	localN = normalizeClientVersion(local)
	latestDisplay = latestN
	return localN, latestN, latestDisplay
}
