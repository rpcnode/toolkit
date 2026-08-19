package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"path"
	"strings"
	"time"
)

type vendoredManifest struct {
	Version            string `json:"version"`
	Tag                string `json:"tag"`
	ArtifactURL        string `json:"artifact_url"`
	ArtifactURLAarch64 string `json:"artifact_url_aarch64"`
	ConfURL            string `json:"conf_url"`
	Files              []vendoredFile
}

type vendoredFile struct {
	Role   string `json:"role"`
	Name   string `json:"name"`
	Path   string `json:"path"`
	Arch   string `json:"arch"`
	Status string `json:"status"`
	URL    string `json:"url"`
}

func clientCatalogRoots() []string {
	base := strings.TrimRight(clientsBaseURL(), "/")
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

// preferVendoredArtifact — CDN manifest first (our fetched dist/), official URL only if CDN miss.
func preferVendoredArtifact(network, env, fallback string) string {
	rel, err := fetchVendoredClientRelease(network, env)
	if err != nil {
		return fallback
	}
	if u := strings.TrimSpace(rel.ArtifactURL); u != "" {
		return u
	}
	return fallback
}

// preferVendoredConf — CDN conf_url first, else official fallback.
func preferVendoredConf(network, env, fallback string) string {
	rel, err := fetchVendoredClientRelease(network, env)
	if err == nil {
		if u := strings.TrimSpace(rel.ConfURL); u != "" {
			return u
		}
	}
	return fallback
}

func vendoredNamedConfURL(network, env, name string) string {
	name = strings.TrimSpace(name)
	if name == "" {
		return ""
	}
	base := strings.TrimRight(clientsBaseURL(), "/")
	return fmt.Sprintf("%s/clients/%s/%s/conf/%s", base, network, env, name)
}

func downloadNamedConf(network, env, name, fallback, dest string) error {
	name = strings.TrimSpace(name)
	for _, root := range clientCatalogRoots() {
		cdn := fmt.Sprintf("%s/clients/%s/%s/conf/%s", root, network, env, name)
		if err := downloadFile(cdn, dest); err == nil {
			return nil
		}
	}
	if strings.TrimSpace(fallback) == "" {
		return fmt.Errorf("cdn miss %s/%s/%s", network, env, name)
	}
	return downloadFile(fallback, dest)
}

func fetchVendoredClientRelease(network, env string) (tronClientRelease, error) {
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
	return tronClientRelease{}, last
}

func fetchVendoredManifestURL(root, network, env string) (tronClientRelease, error) {
	manURL := fmt.Sprintf("%s/clients/%s/%s/manifest.json", strings.TrimRight(root, "/"), network, env)
	client := &http.Client{Timeout: 20 * time.Second}
	req, err := http.NewRequest(http.MethodGet, manURL, nil)
	if err != nil {
		return tronClientRelease{}, err
	}
	req.Header.Set("User-Agent", "rpcnode-api-agent")
	resp, err := client.Do(req)
	if err != nil {
		return tronClientRelease{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return tronClientRelease{}, fmt.Errorf("vendored catalog HTTP %d %s", resp.StatusCode, manURL)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 256<<10))
	if err != nil {
		return tronClientRelease{}, err
	}
	return parseVendoredManifest(network, env, root, raw)
}

func parseVendoredManifest(network, env, installBase string, raw []byte) (tronClientRelease, error) {
	var man vendoredManifest
	if err := json.Unmarshal(raw, &man); err != nil {
		return tronClientRelease{}, err
	}
	jar := pickVendoredArtifact(installBase, man, hostIsARM())
	if jar == "" {
		return tronClientRelease{}, fmt.Errorf("vendored manifest missing artifact on %s/clients", strings.TrimRight(installBase, "/"))
	}
	conf := strings.TrimSpace(man.ConfURL)
	if conf == "" {
		conf = vendoredConfURL(installBase, network, env, man.Files)
	}
	ver := strings.TrimSpace(man.Version)
	if ver == "" {
		ver = strings.TrimSpace(man.Tag)
	}
	return tronClientRelease{
		Version:     ver,
		Tag:         strings.TrimSpace(man.Tag),
		ArtifactURL: jar,
		ConfURL:     conf,
		Source:      "cdn",
	}, nil
}

func vendoredCDNFileURL(root, relPath string) string {
	root = strings.TrimRight(root, "/")
	relPath = strings.TrimPrefix(strings.TrimSpace(relPath), "/")
	if root == "" || relPath == "" {
		return ""
	}
	return root + "/clients/" + path.Clean(relPath)
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
			return u
		}
	}
	if u := strings.TrimSpace(man.ArtifactURL); u != "" && strings.Contains(u, "/clients/") {
		return u
	}
	if chosen != nil && strings.Contains(chosen.URL, "/clients/") {
		return strings.TrimSpace(chosen.URL)
	}
	return pickVendoredJar(man, arm)
}

func pickVendoredJar(man vendoredManifest, arm bool) string {
	jar := strings.TrimSpace(man.ArtifactURL)
	if arm && strings.TrimSpace(man.ArtifactURLAarch64) != "" {
		return strings.TrimSpace(man.ArtifactURLAarch64)
	}
	return jar
}

func hostIsARM() bool {
	arch := runtimeGOARCH()
	return arch == "arm64" || arch == "aarch64"
}
