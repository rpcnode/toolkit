package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
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
	Status string `json:"status"`
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
	cdn := vendoredNamedConfURL(network, env, name)
	if cdn != "" {
		if err := downloadFile(cdn, dest); err == nil {
			return nil
		}
	}
	if strings.TrimSpace(fallback) == "" {
		return fmt.Errorf("cdn miss %s", cdn)
	}
	return downloadFile(fallback, dest)
}

func fetchVendoredClientRelease(network, env string) (tronClientRelease, error) {
	base := clientsBaseURL()
	manURL := fmt.Sprintf("%s/clients/%s/%s/manifest.json", strings.TrimRight(base, "/"), network, env)
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
	return parseVendoredManifest(network, env, base, raw)
}

func parseVendoredManifest(network, env, installBase string, raw []byte) (tronClientRelease, error) {
	var man vendoredManifest
	if err := json.Unmarshal(raw, &man); err != nil {
		return tronClientRelease{}, err
	}
	jar := pickVendoredJar(man, hostIsARM())
	if jar == "" {
		return tronClientRelease{}, fmt.Errorf("vendored manifest missing artifact_url")
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
	arch := runtimeGOARCH()
	return arch == "arm64" || arch == "aarch64"
}
