package main

import (
	"fmt"
	"log"
	"strings"
)

// logDownload — one audit line per URL we resolve or GET (catalog / artifact / conf).
// /var/log/rpcnode.log + unit journal. Query secrets redacted by hostLog.
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
	hostLog("INFO", "api-agent", "download", msg)
	log.Printf("download %s", msg)
}

func logDownloadOK(kind, url, extra string) {
	msg := strings.TrimSpace(kind) + " ok " + strings.TrimSpace(url)
	if extra = strings.TrimSpace(extra); extra != "" {
		msg += "  " + extra
	}
	hostLog("INFO", "api-agent", "download", msg)
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
	hostLog("ERROR", "api-agent", "download", msg)
	log.Printf("download %s", msg)
}

// logDownloadDone — GET/docker/snapshot result after curl/wget/docker pull.
// logProvisionClientCatalog — URLs we will hit, on the same `provision` lines as begin.
func logProvisionClientCatalog(network, env string) {
	network = strings.TrimSpace(network)
	env = strings.TrimSpace(env)
	if network == "" {
		return
	}
	if env == "" {
		env = "mainnet"
	}
	for _, root := range clientCatalogRoots() {
		manURL := fmt.Sprintf("%s/clients/%s/%s/manifest.json", strings.TrimRight(root, "/"), network, env)
		hostLogf("INFO", "api-agent", "provision", "download catalog %s/%s %s", network, env, manURL)
		logDownload("manifest", manURL, network+"/"+env+" (will GET)")
	}
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
