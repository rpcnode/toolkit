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

// tronClientRelease — same catalog shape as system-agent ResolveClientRelease(tron).
// Primary source is the vendored CDN catalog. GitHub / pin is fallback only.
type tronClientRelease struct {
	Version     string
	Tag         string
	ArtifactURL string
	ConfURL     string
	Source      string
}

const tronClientPinTag = "GreatVoyage-v4.8.2.1"

var (
	tronRelCacheMu  sync.Mutex
	tronRelCacheTag string
	tronRelCacheAt  time.Time
	tronRelCacheTTL = 1 * time.Hour
)

func resolveTronClientRelease(env string) tronClientRelease {
	env = normalizeEnv(env)
	if rel, ok := tronEnvOverride(env); ok {
		return rel
	}
	if rel, err := fetchVendoredClientRelease("tron", env); err == nil && strings.TrimSpace(rel.ArtifactURL) != "" {
		return rel
	}
	if strings.TrimSpace(os.Getenv("RPCNODE_CLIENT_RELEASE_PIN")) == "1" {
		return tronReleaseFromTag(tronClientPinTag, env, "pin")
	}
	if tag, err := fetchTronGithubLatestTag(); err == nil && tag != "" {
		return tronReleaseFromTag(tag, env, "github")
	}
	return tronReleaseFromTag(tronClientPinTag, env, "pin")
}

func tronEnvOverride(env string) (tronClientRelease, bool) {
	tag := strings.TrimSpace(os.Getenv("TRON_TAG"))
	jar := strings.TrimSpace(os.Getenv("TRON_JAR_URL"))
	conf := strings.TrimSpace(os.Getenv("TRON_CONFIG_URL"))
	if tag == "" && jar == "" && conf == "" {
		return tronClientRelease{}, false
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

func tronReleaseFromTag(tag, env, source string) tronClientRelease {
	tag = strings.TrimSpace(tag)
	ver := strings.TrimPrefix(strings.TrimPrefix(tag, "GreatVoyage-"), "v")
	ver = strings.TrimPrefix(ver, "V")
	jar := fmt.Sprintf("https://github.com/tronprotocol/java-tron/releases/download/%s/FullNode.jar", tag)
	conf := fmt.Sprintf("https://raw.githubusercontent.com/tronprotocol/java-tron/%s/framework/src/main/resources/config.conf", tag)
	if env == "nile" {
		// Fallback only. Product path is vendored CDN (PQ jar + config-nile.conf).
		jar = "https://github.com/tron-nile-testnet/nile-testnet/releases/download/GreatVoyage-Nile-v4.8.2.1-PQ1-build1/FullNode-Nile-x64-4.8.2.1-pq1-build1.jar"
		if hostIsARM() {
			jar = "https://github.com/tron-nile-testnet/nile-testnet/releases/download/GreatVoyage-Nile-v4.8.2.1-PQ1-build1/FullNode-Nile-aarch64-4.8.2.1-pq1-build1.jar"
		}
		conf = "https://raw.githubusercontent.com/tron-nile-testnet/nile-testnet/master/framework/src/main/resources/config-nile.conf"
	}
	return tronClientRelease{
		Version: ver, Tag: tag, ArtifactURL: jar, ConfURL: conf, Source: source,
	}
}

func fetchTronGithubLatestTag() (string, error) {
	tronRelCacheMu.Lock()
	if tronRelCacheTag != "" && time.Since(tronRelCacheAt) < tronRelCacheTTL {
		tag := tronRelCacheTag
		tronRelCacheMu.Unlock()
		return tag, nil
	}
	tronRelCacheMu.Unlock()

	client := &http.Client{Timeout: 15 * time.Second}
	req, err := http.NewRequest(http.MethodGet, "https://api.github.com/repos/tronprotocol/java-tron/releases?per_page=30", nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "rpcnode-api-agent")
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
		return "", fmt.Errorf("no GreatVoyage release")
	}
	tronRelCacheMu.Lock()
	tronRelCacheTag = tag
	tronRelCacheAt = time.Now()
	tronRelCacheMu.Unlock()
	return tag, nil
}
