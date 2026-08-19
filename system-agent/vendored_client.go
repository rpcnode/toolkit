package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"path"
	"runtime"
	"strings"
	"time"
)

type vendoredManifest struct {
	Version            string `json:"version"`
	Tag                string `json:"tag"`
	ArtifactURL        string `json:"artifact_url"`
	ArtifactURLAarch64 string `json:"artifact_url_aarch64"`
	SHA256             string `json:"sha256"`
	ConfURL            string `json:"conf_url"`
	ArtifactKind       string `json:"artifact_kind"`
	NeedsConfPatch     bool   `json:"needs_conf_patch"`
	Notes              string `json:"notes"`
	Files              []vendoredFile
}

type vendoredFile struct {
	Role   string `json:"role"`
	Name   string `json:"name"`
	Path   string `json:"path"`
	Arch   string `json:"arch"`
	Status string `json:"status"`
	URL    string `json:"url"`
	Kind   string `json:"kind"`
}

func clientCatalogRoots() []string {
	base := strings.TrimRight(clientInstallBaseURL(), "/")
	// Product pin is toolkit.rpcnode.dev/install/clients/… — try /install first.
	var roots []string
	if strings.HasSuffix(base, "/install") {
		roots = []string{base, strings.TrimSuffix(base, "/install")}
	} else {
		roots = []string{base + "/install", base}
	}
	seen := map[string]bool{}
	out := make([]string, 0, len(roots))
	for _, r := range roots {
		r = strings.TrimRight(r, "/")
		if r == "" || seen[r] {
			continue
		}
		seen[r] = true
		out = append(out, r)
	}
	return out
}

func fetchVendoredClientRelease(network, env string) (ClientRelease, error) {
	var last error
	for _, root := range clientCatalogRoots() {
		rel, err := fetchVendoredManifestURL(root, network, env)
		if err == nil && strings.TrimSpace(rel.ArtifactURL) != "" {
			return rel, nil
		}
		if err != nil {
			last = err
		} else {
			last = fmt.Errorf("%s/clients/%s/%s/manifest.json: no artifact", root, network, env)
		}
	}
	if last == nil {
		last = fmt.Errorf("no manifest URL")
	}
	return ClientRelease{}, last
}

func fetchVendoredManifestURL(root, network, env string) (ClientRelease, error) {
	manURL := fmt.Sprintf("%s/clients/%s/%s/manifest.json", strings.TrimRight(root, "/"), network, env)
	logDownload("manifest", manURL, network+"/"+env)
	client := &http.Client{Timeout: 20 * time.Second}
	req, err := http.NewRequest(http.MethodGet, manURL, nil)
	if err != nil {
		logDownloadFail("manifest", manURL, err)
		return ClientRelease{}, err
	}
	req.Header.Set("User-Agent", "rpcnode-system-agent")
	resp, err := client.Do(req)
	if err != nil {
		logDownloadFail("manifest", manURL, err)
		return ClientRelease{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		err := fmt.Errorf("vendored catalog HTTP %d %s", resp.StatusCode, manURL)
		logDownloadFail("manifest", manURL, err)
		return ClientRelease{}, err
	}
	logDownloadOK("manifest", manURL, fmt.Sprintf("HTTP %d %s/%s", resp.StatusCode, network, env))
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 256<<10))
	if err != nil {
		logDownloadFail("manifest", manURL, err)
		return ClientRelease{}, err
	}
	return parseVendoredManifest(network, env, root, raw)
}

func parseVendoredManifest(network, env, installBase string, raw []byte) (ClientRelease, error) {
	var man vendoredManifest
	if err := json.Unmarshal(raw, &man); err != nil {
		return ClientRelease{}, err
	}
	jar := pickVendoredArtifact(installBase, man, hostIsARM())
	if jar == "" {
		return ClientRelease{}, fmt.Errorf("vendored manifest missing artifact on %s/clients", strings.TrimRight(installBase, "/"))
	}
	conf := strings.TrimSpace(man.ConfURL)
	if conf == "" {
		conf = vendoredConfURL(installBase, network, env, man.Files)
	}
	kind := strings.TrimSpace(man.ArtifactKind)
	if kind == "" {
		for _, f := range man.Files {
			if strings.EqualFold(f.Role, "artifact") && strings.TrimSpace(f.Kind) != "" {
				kind = strings.TrimSpace(f.Kind)
				break
			}
		}
	}
	if kind == "" {
		kind = "jar"
	}
	return ClientRelease{
		Network:        network,
		Env:            env,
		Version:        normalizeClientVersion(firstNonEmptyStr(man.Version, man.Tag)),
		Tag:            strings.TrimSpace(man.Tag),
		ArtifactURL:    jar,
		SHA256:         strings.TrimSpace(man.SHA256),
		ConfURL:        conf,
		ArtifactKind:   kind,
		NeedsConfPatch: man.NeedsConfPatch,
		Source:         "cdn",
		Notes:          firstNonEmptyStr(man.Notes, "vendored "+network+"/"+env),
	}, nil
}

func rewriteDeadClientsCDNURL(u string) string {
	const dead = "https://toolkit.rpcnode.dev/clients/"
	const live = "https://toolkit.rpcnode.dev/install/clients/"
	if strings.HasPrefix(u, dead) {
		fixed := live + strings.TrimPrefix(u, dead)
		logDownload("rewrite", u, "→ "+fixed)
		return fixed
	}
	return u
}

func vendoredCDNFileURL(root, relPath string) string {
	root = strings.TrimRight(root, "/")
	relPath = strings.TrimPrefix(strings.TrimSpace(relPath), "/")
	if root == "" || relPath == "" {
		return ""
	}
	return rewriteDeadClientsCDNURL(root + "/clients/" + path.Clean(relPath))
}

func vendoredConfURL(installBase, network, env string, files []vendoredFile) string {
	base := strings.TrimRight(installBase, "/")
	for _, f := range files {
		if f.Role != "config" || strings.EqualFold(f.Status, "apt") {
			continue
		}
		if p := strings.TrimSpace(f.Path); p != "" {
			return vendoredCDNFileURL(base, p)
		}
		name := strings.TrimSpace(f.Name)
		if name == "" {
			continue
		}
		return fmt.Sprintf("%s/clients/%s/%s/conf/%s", base, network, env, name)
	}
	return ""
}

// pickVendoredArtifact — file on this CDN (`files[].path`), not GitHub / watcher IP.
func pickVendoredArtifact(root string, man vendoredManifest, arm bool) string {
	var chosen *vendoredFile
	for i := range man.Files {
		f := &man.Files[i]
		if !strings.EqualFold(f.Role, "artifact") || strings.EqualFold(f.Status, "apt") {
			continue
		}
		arch := strings.ToLower(strings.TrimSpace(f.Arch))
		if arm && (arch == "aarch64" || arch == "arm64") {
			chosen = f
			break
		}
		if !arm && (arch == "x86_64" || arch == "amd64" || arch == "") {
			if chosen == nil {
				chosen = f
			}
		}
		if chosen == nil {
			chosen = f
		}
	}
	if chosen != nil {
		if u := vendoredCDNFileURL(root, chosen.Path); u != "" {
			return u
		}
	}
	if arm {
		if u := strings.TrimSpace(man.ArtifactURLAarch64); u != "" && strings.Contains(u, "/clients/") {
			return rewriteDeadClientsCDNURL(u)
		}
	}
	if u := strings.TrimSpace(man.ArtifactURL); u != "" && strings.Contains(u, "/clients/") {
		return rewriteDeadClientsCDNURL(u)
	}
	if chosen != nil && strings.Contains(chosen.URL, "/clients/") {
		return rewriteDeadClientsCDNURL(strings.TrimSpace(chosen.URL))
	}
	return ""
}

func pickVendoredJar(man vendoredManifest, arm bool) string {
	return pickVendoredArtifact("", man, arm)
}

func hostIsARM() bool {
	return runtime.GOARCH == "arm64" || runtime.GOARCH == "aarch64"
}
