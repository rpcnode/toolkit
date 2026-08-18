package main

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestRequiredSnapshotFreeBytesMargin(t *testing.T) {
	archive := int64(100) * bytesPerGiB
	need := requiredSnapshotFreeBytes(archive, "sui")
	// 100 × 2.0 unpack = 200; +15% = 230 GiB (download-then-extract)
	want := int64(230) * bytesPerGiB
	if need != want {
		t.Fatalf("sui required=%d (%s) want %d (%s)", need, formatGiB(need), want, formatGiB(want))
	}
	// TRON streams wget|tar — no tgz on disk: 100 × 1.0 + 15% = 115 GiB
	tronNeed := requiredSnapshotFreeBytes(archive, "tron")
	tronWant := int64(115) * bytesPerGiB
	if tronNeed != tronWant {
		t.Fatalf("tron required=%d (%s) want %d (%s)", tronNeed, formatGiB(tronNeed), tronWant, formatGiB(tronWant))
	}
	// Cardano Mithril moves db/ in place — same 1.0 stream multiplier.
	adaNeed := requiredSnapshotFreeBytes(archive, "cardano")
	if adaNeed != tronWant {
		t.Fatalf("cardano required=%d (%s) want %d", adaNeed, formatGiB(adaNeed), tronWant)
	}
}

func TestRequiredSnapshotFreeBytesDefaultWhenZero(t *testing.T) {
	need := requiredSnapshotFreeBytes(0, "sui")
	base := int64(snapDiskDefaultArchiveGiB) * bytesPerGiB
	want := requiredSnapshotFreeBytes(base, "sui")
	if need != want {
		t.Fatalf("zero archive should use default: got %d want %d", need, want)
	}
}

func TestInsufficientDiskMessageAndDetect(t *testing.T) {
	est := snapDiskEstimate{
		ArchiveBytes:  100 * bytesPerGiB,
		Source:        "default",
		RequiredBytes: 230 * bytesPerGiB,
		FreeBytes:     50 * bytesPerGiB,
		DataPath:      "/data/tron/mainnet",
	}
	msg := insufficientDiskMessage(est)
	if !strings.Contains(msg, "insufficient disk") {
		t.Fatalf("msg=%q", msg)
	}
	if !isInsufficientDiskError(msg) {
		t.Fatal("detector should match generated message")
	}
	if !isInsufficientDiskError("wget: No space left on device") {
		t.Fatal("should match classic ENOSPC wording")
	}
	if isInsufficientDiskError("snapshot already running") {
		t.Fatal("must not treat unrelated errors as disk")
	}
}

func TestProbeSnapshotContentLength(t *testing.T) {
	// Reset cache between tests.
	snapLenMu.Lock()
	snapLenURL, snapLenBytes, snapLenAt = "", 0, snapLenAt.Add(-snapLenCacheTTL*2)
	snapLenMu.Unlock()

	const size = 1234567890
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodHead {
			w.Header().Set("Content-Length", "1234567890")
			w.WriteHeader(http.StatusOK)
			return
		}
		http.Error(w, "no body", http.StatusOK)
	}))
	defer srv.Close()

	n, src := probeSnapshotContentLength(srv.URL)
	if n != size {
		t.Fatalf("Content-Length=%d want %d (src=%s)", n, size, src)
	}
	if src != "content-length" {
		t.Fatalf("src=%s", src)
	}

	// Second call hits cache.
	n2, src2 := probeSnapshotContentLength(srv.URL)
	if n2 != size || src2 != "content-length-cache" {
		t.Fatalf("cache miss: n=%d src=%s", n2, src2)
	}
}

func TestCheckSnapshotDiskSpaceBlocksWhenLow(t *testing.T) {
	// Host FS cannot be shrunk in CI — assert the gate math + error surfacing.
	est := snapDiskEstimate{
		ArchiveBytes:  defaultSnapshotArchiveBytes("tron", "mainnet"),
		Source:        "default",
		RequiredBytes: requiredSnapshotFreeBytes(defaultSnapshotArchiveBytes("tron", "mainnet"), "tron"),
		FreeBytes:     10 * bytesPerGiB,
		DataPath:      "/data/tron/mainnet",
		ExtractMult:   snapDiskStreamExtractMult,
		Stream:        true,
	}
	if est.FreeBytes >= est.RequiredBytes {
		t.Fatal("test fixture should be below required")
	}
	msg := insufficientDiskMessage(est)
	if !isInsufficientDiskError(msg) {
		t.Fatalf("msg=%q", msg)
	}
	if !strings.Contains(msg, "stream unpack") {
		t.Fatalf("tron message should say stream unpack: %q", msg)
	}
	if est.RequiredBytes < int64(snapDiskAbortFloorGiB)*bytesPerGiB {
		t.Fatalf("required too small: %s", formatGiB(est.RequiredBytes))
	}
	// 500 GiB archive → stream ×1.0 + 15% = 575 GiB required
	want := int64(575) * bytesPerGiB
	if est.RequiredBytes != want {
		t.Fatalf("mainnet required=%s want %s", formatGiB(est.RequiredBytes), formatGiB(want))
	}
}

func TestLowDiskDuringSnapshotMessage(t *testing.T) {
	msg := lowDiskDuringSnapshotMessage(5*bytesPerGiB, "/data/tron/mainnet")
	if !isInsufficientDiskError(msg) {
		t.Fatalf("msg=%q", msg)
	}
	if !strings.Contains(msg, "abort floor") {
		t.Fatalf("msg=%q", msg)
	}
}

func TestDefaultSnapshotArchiveBytesByPolicy(t *testing.T) {
	main := defaultSnapshotArchiveBytes("tron", "mainnet")
	opt := defaultSnapshotArchiveBytes("tron", "shasta")
	if main != int64(snapDiskDefaultArchiveGiB)*bytesPerGiB {
		t.Fatalf("mainnet default=%d", main)
	}
	if opt >= main {
		t.Fatalf("optional env should use smaller default: opt=%d main=%d", opt, main)
	}
}
