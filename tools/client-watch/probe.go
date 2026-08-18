package main

import (
	"fmt"
	"net/http"
	"strings"
	"time"
)

func (e catalogEntry) httpProbeURL() string {
	for _, a := range e.Artifacts {
		if a.isApt() {
			continue
		}
		u := strings.TrimSpace(a.URL)
		if strings.HasPrefix(u, "http://") || strings.HasPrefix(u, "https://") {
			return u
		}
	}
	return ""
}

func probeHTTP(client *http.Client, raw string) (ghLatest, error) {
	var empty ghLatest
	req, err := http.NewRequest(http.MethodHead, raw, nil)
	if err != nil {
		return empty, err
	}
	req.Header.Set("User-Agent", "rpcnode-client-watch")
	resp, err := client.Do(req)
	if err != nil {
		return empty, err
	}
	_ = resp.Body.Close()
	if resp.StatusCode == http.StatusMethodNotAllowed || resp.StatusCode == http.StatusForbidden {
		req, err = http.NewRequest(http.MethodGet, raw, nil)
		if err != nil {
			return empty, err
		}
		req.Header.Set("User-Agent", "rpcnode-client-watch")
		req.Header.Set("Range", "bytes=0-0")
		resp, err = client.Do(req)
		if err != nil {
			return empty, err
		}
		_ = resp.Body.Close()
	}
	if resp.StatusCode >= 300 {
		return empty, fmt.Errorf("HTTP %d %s", resp.StatusCode, raw)
	}
	ver := httpFingerprint(resp.Header.Get("ETag"), resp.Header.Get("Last-Modified"))
	if ver == "" {
		return empty, fmt.Errorf("нет ETag/Last-Modified: %s", raw)
	}
	return ghLatest{Version: ver, Tag: strings.Trim(resp.Header.Get("ETag"), `"`)}, nil
}

func httpFingerprint(etag, lastModified string) string {
	etag = strings.TrimSpace(etag)
	etag = strings.TrimPrefix(etag, "W/")
	etag = strings.TrimSpace(strings.Trim(etag, `"`))
	short := etag
	if len(short) > 8 {
		short = short[:8]
	}
	day := parseHTTPDate(lastModified)
	switch {
	case day != "" && short != "":
		return day + "-" + short
	case short != "":
		return short
	default:
		return day
	}
}

func parseHTTPDate(raw string) string {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return ""
	}
	for _, layout := range []string{time.RFC1123, time.RFC1123Z, time.RFC850, time.ANSIC} {
		if t, err := time.Parse(layout, raw); err == nil {
			return t.UTC().Format("2006-01-02")
		}
	}
	return ""
}
