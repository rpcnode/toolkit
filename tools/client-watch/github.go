package main

import (
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

type ghRelease struct {
	TagName string    `json:"tag_name"`
	Draft   bool      `json:"draft"`
	Assets  []ghAsset `json:"assets"`
}

type ghAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
}

type ghLatest struct {
	Version string
	Tag     string
	Assets  []ghAsset
}

func fetchLatest(client *http.Client, repo, prefix, token string) (ghLatest, error) {
	var empty ghLatest
	req, err := http.NewRequest(http.MethodGet, "https://api.github.com/repos/"+repo+"/releases?per_page=20", nil)
	if err != nil {
		return empty, err
	}
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "rpcnode-client-watch")
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := client.Do(req)
	if err != nil {
		return empty, err
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 2<<20))
	if resp.StatusCode >= 300 {
		return empty, fmt.Errorf("GitHub HTTP %d %s", resp.StatusCode, strings.TrimSpace(string(body)))
	}
	var rels []ghRelease
	if err := json.Unmarshal(body, &rels); err != nil {
		return empty, err
	}
	for _, rel := range rels {
		if rel.Draft {
			continue
		}
		tag := strings.TrimSpace(rel.TagName)
		if prefix != "" && !strings.HasPrefix(tag, prefix) {
			continue
		}
		return ghLatest{Version: displayVersion(tag), Tag: tag, Assets: rel.Assets}, nil
	}
	return empty, nil
}

func httpClient() *http.Client {
	return &http.Client{Timeout: 45 * time.Second}
}
