package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"runtime"
	"strings"
	"time"
)

type vendoredManifest struct {
	Version            string `json:"version"`
	Tag                string `json:"tag"`
	ArtifactURL        string `json:"artifact_url"`
	ArtifactURLAarch64 string `json:"artifact_url_aarch64"`
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
}

func fetchVendoredClientRelease(network, env string) (ClientRelease, error) {
	base := clientInstallBaseURL()
	manURL := fmt.Sprintf("%s/clients/%s/%s/manifest.json", strings.TrimRight(base, "/"), network, env)
	client := &http.Client{Timeout: 20 * time.Second}
	req, err := http.NewRequest(http.MethodGet, manURL, nil)
	if err != nil {
		return ClientRelease{}, err
	}
	req.Header.Set("User-Agent", "rpcnode-system-agent")
	resp, err := client.Do(req)
	if err != nil {
		return ClientRelease{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode >= 300 {
		return ClientRelease{}, fmt.Errorf("vendored catalog HTTP %d %s", resp.StatusCode, manURL)
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, 256<<10))
	if err != nil {
		return ClientRelease{}, err
	}
	return parseVendoredManifest(network, env, base, raw)
}

func parseVendoredManifest(network, env, installBase string, raw []byte) (ClientRelease, error) {
	var man vendoredManifest
	if err := json.Unmarshal(raw, &man); err != nil {
		return ClientRelease{}, err
	}
	jar := pickVendoredJar(man, hostIsARM())
	if jar == "" {
		return ClientRelease{}, fmt.Errorf("vendored manifest missing artifact_url")
	}
	conf := strings.TrimSpace(man.ConfURL)
	if conf == "" {
		conf = vendoredConfURL(installBase, network, env, man.Files)
	}
	kind := strings.TrimSpace(man.ArtifactKind)
	if kind == "" {
		kind = "jar"
	}
	return ClientRelease{
		Network:        network,
		Env:            env,
		Version:        normalizeClientVersion(firstNonEmptyStr(man.Version, man.Tag)),
		Tag:            strings.TrimSpace(man.Tag),
		ArtifactURL:    jar,
		ConfURL:        conf,
		ArtifactKind:   kind,
		NeedsConfPatch: man.NeedsConfPatch,
		Source:         "cdn",
		Notes:          firstNonEmptyStr(man.Notes, "vendored "+network+"/"+env),
	}, nil
}

func vendoredConfURL(installBase, network, env string, files []vendoredFile) string {
	base := strings.TrimRight(installBase, "/")
	for _, f := range files {
		if f.Role != "config" || strings.EqualFold(f.Status, "apt") {
			continue
		}
		name := strings.TrimSpace(f.Name)
		if name == "" {
			continue
		}
		return fmt.Sprintf("%s/clients/%s/%s/conf/%s", base, network, env, name)
	}
	return ""
}

func pickVendoredJar(man vendoredManifest, arm bool) string {
	jar := strings.TrimSpace(man.ArtifactURL)
	if arm && strings.TrimSpace(man.ArtifactURLAarch64) != "" {
		return strings.TrimSpace(man.ArtifactURLAarch64)
	}
	return jar
}

func hostIsARM() bool {
	return runtime.GOARCH == "arm64" || runtime.GOARCH == "aarch64"
}
