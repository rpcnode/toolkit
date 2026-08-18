package main

import (
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

// Cached CDN TOOLKIT_VERSION for Servers "update available" affordance.
// Latest comes from the same install channel as curl|bash agent.sh — not SQLite.
// Per-server agent_version remains the installed binary on that host.
var (
	cdnVerMu    sync.Mutex
	cdnVerValue string
	cdnVerAt    time.Time
)

func canonToolkitCDNHost(u string) string {
	u = strings.TrimSpace(u)
	if u == "" {
		return u
	}
	for _, old := range []string{
		"https://www.rpcnode.dev/",
		"http://www.rpcnode.dev/",
		"https://rpcnode.dev/",
		"http://rpcnode.dev/",
	} {
		if strings.HasPrefix(u, old) {
			return "https://toolkit.rpcnode.dev/" + strings.TrimPrefix(u, old)
		}
	}
	return u
}

func installBaseURL() string {
	var u string
	if v := strings.TrimSpace(os.Getenv("INSTALL_BASE_URL")); v != "" {
		u = strings.TrimRight(v, "/")
	} else if v := strings.TrimSpace(os.Getenv("AGENT_DOWNLOAD_URL")); v != "" {
		u = strings.TrimRight(v, "/")
		u = strings.TrimSuffix(u, "/agent.sh")
	} else {
		u = "https://toolkit.rpcnode.dev/install"
	}
	return strings.TrimRight(canonToolkitCDNHost(u), "/")
}

func toolkitVersionURL() string {
	if u := strings.TrimSpace(os.Getenv("TOOLKIT_VERSION_URL")); u != "" {
		return canonToolkitCDNHost(u)
	}
	return installBaseURL() + "/TOOLKIT_VERSION"
}

func (s *Server) cdnToolkitVersion() string {
	return getCDNToolkitVersion(false)
}

func (s *Server) cdnToolkitVersionEx(force bool) string {
	return getCDNToolkitVersion(force)
}

// getCDNToolkitVersion fetches/caches install CDN TOOLKIT_VERSION (panel + collector).
func getCDNToolkitVersion(force bool) string {
	cdnVerMu.Lock()
	defer cdnVerMu.Unlock()
	if !force && cdnVerValue != "" && time.Since(cdnVerAt) < 5*time.Minute {
		return cdnVerValue
	}
	client := &http.Client{Timeout: 8 * time.Second}
	req, err := http.NewRequest(http.MethodGet, toolkitVersionURL(), nil)
	if err != nil {
		return cdnVerValue
	}
	resp, err := client.Do(req)
	if err != nil {
		return cdnVerValue
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return cdnVerValue
	}
	b, err := io.ReadAll(io.LimitReader(resp.Body, 256))
	if err != nil {
		return cdnVerValue
	}
	v := strings.TrimSpace(string(b))
	if v == "" {
		return cdnVerValue
	}
	cdnVerValue = v
	cdnVerAt = time.Now()
	return cdnVerValue
}

// handleAgentChannel — panel-owned CDN channel (NOT proxied to host agent).
// Same source as install: https://toolkit.rpcnode.dev/install/TOOLKIT_VERSION (+ agent.sh).
func (s *Server) handleAgentChannel(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "GET only", http.StatusMethodNotAllowed)
		return
	}
	force := strings.EqualFold(r.URL.Query().Get("refresh"), "1") ||
		strings.EqualFold(r.URL.Query().Get("refresh"), "true")
	base := installBaseURL()
	ver := s.cdnToolkitVersionEx(force)
	writeJSON(w, http.StatusOK, map[string]any{
		"ok":            true,
		"role":          "panel",
		"version":       ver,
		"channel":       toolkitVersionURL(),
		"install_url":   base + "/agent.sh",
		"binaries_base": base + "/binaries",
		"cached_at": func() string {
			cdnVerMu.Lock()
			defer cdnVerMu.Unlock()
			if cdnVerAt.IsZero() {
				return ""
			}
			return cdnVerAt.UTC().Format(time.RFC3339)
		}(),
		"note": "Latest toolkit/agent version from install CDN — compare to per-server agent_version (installed).",
	})
}

// agentVersionOutdated reports whether local is older than remote (semver-ish).
// Equal or empty → not outdated. Non-numeric tails compared lexicographically.
func agentVersionOutdated(local, remote string) bool {
	local = strings.TrimSpace(strings.TrimPrefix(local, "v"))
	remote = strings.TrimSpace(strings.TrimPrefix(remote, "v"))
	if local == "" || remote == "" {
		return false
	}
	if local == remote {
		return false
	}
	return versionCompare(local, remote) < 0
}

func versionCompare(a, b string) int {
	as := strings.Split(a, ".")
	bs := strings.Split(b, ".")
	n := len(as)
	if len(bs) > n {
		n = len(bs)
	}
	for i := 0; i < n; i++ {
		var ai, bi string
		if i < len(as) {
			ai = as[i]
		}
		if i < len(bs) {
			bi = bs[i]
		}
		an, aErr := strconv.Atoi(digitsPrefix(ai))
		bn, bErr := strconv.Atoi(digitsPrefix(bi))
		if aErr == nil && bErr == nil {
			if an < bn {
				return -1
			}
			if an > bn {
				return 1
			}
			// same numeric prefix — compare remainder (e.g. 1rc vs 1)
			ar := strings.TrimPrefix(ai, strconv.Itoa(an))
			br := strings.TrimPrefix(bi, strconv.Itoa(bn))
			if ar == br {
				continue
			}
			if ar == "" && br != "" {
				return 1 // 1.0 > 1.0rc
			}
			if ar != "" && br == "" {
				return -1
			}
			if ar < br {
				return -1
			}
			if ar > br {
				return 1
			}
			continue
		}
		if ai < bi {
			return -1
		}
		if ai > bi {
			return 1
		}
	}
	return 0
}

func digitsPrefix(s string) string {
	i := 0
	for i < len(s) && s[i] >= '0' && s[i] <= '9' {
		i++
	}
	if i == 0 {
		return "0"
	}
	return s[:i]
}
