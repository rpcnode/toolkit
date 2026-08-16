package main

import (
	"context"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

const (
	bytesPerGiB = 1024 * 1024 * 1024

	// Download-then-extract (archive stays on disk next to output).
	snapDiskExtractMult = 2.0
	// TRON: wget -O - | tar -xzf — no tgz on disk; reserve compressed size + margin.
	snapDiskStreamExtractMult = 1.0
	// Extra headroom on top of archive×extract.
	snapDiskMarginPct = 15
	// When Content-Length is unknown — conservative TRON FullNode tgz ballpark.
	snapDiskDefaultArchiveGiB = 500
	// During download: abort if free space falls below this floor (ENOSPC guard).
	snapDiskAbortFloorGiB = 20
	snapDiskHEADTimeout   = 12 * time.Second
)

// snapDiskEstimate — archive size guess used for the pre-start gate.
type snapDiskEstimate struct {
	ArchiveBytes  int64
	Source        string // content-length | default | override
	RequiredBytes int64
	FreeBytes     int64
	DataPath      string
	ExtractMult   float64
	Stream        bool
}

func snapshotExtractMult(network string) float64 {
	if strings.EqualFold(strings.TrimSpace(network), "tron") {
		return snapDiskStreamExtractMult
	}
	return snapDiskExtractMult
}

func gib(bytes int64) float64 {
	if bytes <= 0 {
		return 0
	}
	return float64(bytes) / float64(bytesPerGiB)
}

func formatGiB(bytes int64) string {
	return fmt.Sprintf("%.0f GiB", gib(bytes))
}

// requiredSnapshotFreeBytes — archive × extract multiplier + margin %.
// TRON streams (wget | tar) so multiplier is 1.0 — do not reserve a second copy of the tgz.
func requiredSnapshotFreeBytes(archiveBytes int64, network string) int64 {
	if archiveBytes <= 0 {
		archiveBytes = int64(snapDiskDefaultArchiveGiB) * bytesPerGiB
	}
	mult := snapshotExtractMult(network)
	withExtract := int64(float64(archiveBytes) * mult)
	margin := withExtract * int64(snapDiskMarginPct) / 100
	return withExtract + margin
}

func defaultSnapshotArchiveBytes(network, env string) int64 {
	np := LookupNetworkProfile(network, env)
	switch np.SnapshotPolicy {
	case SnapshotRequired:
		return int64(snapDiskDefaultArchiveGiB) * bytesPerGiB
	case SnapshotOptional:
		// Optional envs rarely ship a large default URL; keep a smaller floor.
		return 100 * bytesPerGiB
	default:
		return int64(snapDiskDefaultArchiveGiB) * bytesPerGiB
	}
}

var (
	snapLenMu    sync.Mutex
	snapLenURL   string
	snapLenBytes int64
	snapLenAt    time.Time
)

const snapLenCacheTTL = 30 * time.Minute

// probeSnapshotContentLength — HEAD (then Range GET) for Content-Length.
// Cached per URL so pipeline recovery ticks do not hammer the snapshot host.
// Returns 0 when unknown (caller uses default).
func probeSnapshotContentLength(url string) (int64, string) {
	url = strings.TrimSpace(url)
	if url == "" {
		return 0, ""
	}

	snapLenMu.Lock()
	if url == snapLenURL && snapLenBytes > 0 && time.Since(snapLenAt) < snapLenCacheTTL {
		n := snapLenBytes
		snapLenMu.Unlock()
		return n, "content-length-cache"
	}
	snapLenMu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), snapDiskHEADTimeout)
	defer cancel()

	client := &http.Client{Timeout: snapDiskHEADTimeout}
	try := func(method string, hdr map[string]string) (int64, string) {
		req, err := http.NewRequestWithContext(ctx, method, url, nil)
		if err != nil {
			return 0, ""
		}
		for k, v := range hdr {
			req.Header.Set(k, v)
		}
		resp, err := client.Do(req)
		if err != nil {
			return 0, ""
		}
		defer resp.Body.Close()
		if resp.StatusCode >= 200 && resp.StatusCode < 400 && resp.ContentLength > 0 {
			return resp.ContentLength, "content-length"
		}
		return 0, ""
	}

	n, src := try(http.MethodHead, nil)
	if n <= 0 {
		// Some snapshot hosts ignore HEAD — cheap ranged GET.
		n, src = try(http.MethodGet, map[string]string{"Range": "bytes=0-0"})
	}
	if n > 0 {
		snapLenMu.Lock()
		snapLenURL = url
		snapLenBytes = n
		snapLenAt = time.Now()
		snapLenMu.Unlock()
		return n, src
	}
	return 0, ""
}

func mustDiskFreeBytes(path string) int64 {
	n, _ := diskFreeBytes(path)
	return n
}

// cachedOrDefaultArchiveBytes — never blocks on network; uses Content-Length cache or profile default.
func cachedOrDefaultArchiveBytes(cfg Config) int64 {
	url := strings.TrimSpace(cfg.SnapshotURL)
	if url != "" {
		snapLenMu.Lock()
		if url == snapLenURL && snapLenBytes > 0 && time.Since(snapLenAt) < snapLenCacheTTL {
			n := snapLenBytes
			snapLenMu.Unlock()
			return n
		}
		snapLenMu.Unlock()
	}
	return defaultSnapshotArchiveBytes(cfg.Network, cfg.Env)
}

func diskFreeBytes(path string) (int64, error) {
	probe := path
	for {
		if st, err := os.Stat(probe); err == nil && st.IsDir() {
			break
		}
		parent := filepath.Dir(probe)
		if parent == probe {
			probe = "/"
			break
		}
		probe = parent
	}
	avail, _, _ := diskFreeGB(probe)
	if avail <= 0 {
		// diskFreeGB returns int GB; distinguish "unknown" from truly empty.
		if _, err := os.Stat(probe); err != nil && path != "/" {
			avail2, _, _ := diskFreeGB("/")
			return int64(avail2) * bytesPerGiB, nil
		}
	}
	return int64(avail) * bytesPerGiB, nil
}

func estimateSnapshotDisk(cfg Config) snapDiskEstimate {
	path := cfg.DataDir
	if path == "" {
		path = "/"
	}
	free, _ := diskFreeBytes(path)
	archive := int64(0)
	source := "default"
	if n, src := probeSnapshotContentLength(cfg.SnapshotURL); n > 0 {
		archive = n
		source = src
	} else {
		archive = defaultSnapshotArchiveBytes(cfg.Network, cfg.Env)
		source = "default"
	}
	mult := snapshotExtractMult(cfg.Network)
	return snapDiskEstimate{
		ArchiveBytes:  archive,
		Source:        source,
		RequiredBytes: requiredSnapshotFreeBytes(archive, cfg.Network),
		FreeBytes:     free,
		DataPath:      path,
		ExtractMult:   mult,
		Stream:        mult <= snapDiskStreamExtractMult,
	}
}

func insufficientDiskMessage(est snapDiskEstimate) string {
	how := fmt.Sprintf("×%.1f unpack + %d%% margin", est.ExtractMult, snapDiskMarginPct)
	if est.Stream || est.ExtractMult <= snapDiskStreamExtractMult {
		how = fmt.Sprintf("stream unpack + %d%% margin", snapDiskMarginPct)
	}
	return fmt.Sprintf(
		"insufficient disk for snapshot: free≈%s on %s, need≥%s (archive≈%s via %s, %s)",
		formatGiB(est.FreeBytes),
		est.DataPath,
		formatGiB(est.RequiredBytes),
		formatGiB(est.ArchiveBytes),
		est.Source,
		how,
	)
}

func isInsufficientDiskError(msg string) bool {
	low := strings.ToLower(strings.TrimSpace(msg))
	return strings.Contains(low, "insufficient disk") ||
		strings.Contains(low, "no space left") ||
		strings.Contains(low, "disk space")
}

// checkSnapshotDiskSpace — pre-start gate. nil = enough free space.
func checkSnapshotDiskSpace(cfg Config) error {
	est := estimateSnapshotDisk(cfg)
	if est.FreeBytes <= 0 && est.RequiredBytes > 0 {
		// Fail closed when df is unreadable — better than filling the rootfs blind.
		return fmt.Errorf("%s", insufficientDiskMessage(est))
	}
	if est.FreeBytes < est.RequiredBytes {
		return fmt.Errorf("%s", insufficientDiskMessage(est))
	}
	return nil
}

func lowDiskDuringSnapshotMessage(freeBytes int64, path string) string {
	return fmt.Sprintf(
		"insufficient disk during snapshot: free≈%s on %s (abort floor %d GiB) — download stopped",
		formatGiB(freeBytes),
		path,
		snapDiskAbortFloorGiB,
	)
}

// checkSnapshotDiskAbort — mid-download floor. nil = continue.
func checkSnapshotDiskAbort(cfg Config) error {
	path := cfg.DataDir
	if path == "" {
		path = "/"
	}
	free, err := diskFreeBytes(path)
	if err != nil {
		return nil // fail-open on probe errors mid-flight
	}
	floor := int64(snapDiskAbortFloorGiB) * bytesPerGiB
	if free < floor {
		return fmt.Errorf("%s", lowDiskDuringSnapshotMessage(free, path))
	}
	return nil
}
