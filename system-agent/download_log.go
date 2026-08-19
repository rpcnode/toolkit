package main

import (
	"fmt"
	"log"
	"strings"
)

func logDownload(kind, url, extra string) {
	url = strings.TrimSpace(url)
	kind = strings.TrimSpace(kind)
	if url == "" {
		return
	}
	msg := kind + " " + url
	if extra = strings.TrimSpace(extra); extra != "" {
		msg += "  " + extra
	}
	hostLog("INFO", "system-agent", "download", msg)
	log.Printf("download %s", msg)
}

func logDownloadOK(kind, url, extra string) {
	msg := strings.TrimSpace(kind) + " ok " + strings.TrimSpace(url)
	if extra = strings.TrimSpace(extra); extra != "" {
		msg += "  " + extra
	}
	hostLog("INFO", "system-agent", "download", msg)
	log.Printf("download %s", msg)
}

func logDownloadFail(kind, url string, err error) {
	if err == nil {
		return
	}
	u := strings.TrimSpace(url)
	if u == "" {
		u = "(empty)"
	}
	msg := strings.TrimSpace(kind) + " FAIL " + u + "  " + err.Error()
	hostLog("ERROR", "system-agent", "download", msg)
	log.Printf("download %s", msg)
}

func logDownloadDone(kind, url, extra string, out []byte, err error) {
	if err != nil {
		if msg := strings.TrimSpace(string(out)); msg != "" {
			logDownloadFail(kind, url, fmt.Errorf("%v: %s", err, msg))
			return
		}
		logDownloadFail(kind, url, err)
		return
	}
	logDownloadOK(kind, url, extra)
}
