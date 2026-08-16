package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFormatSolanaDownloadProgress(t *testing.T) {
	line := `[2026-08-10T09:42:45Z INFO  solana_file_download] downloaded 6045773488 bytes 5.5% 17854978.0 bytes/s`
	got := formatSolanaDownloadProgress(line)
	if got == "" || !strings.Contains(got, "5.5%") || !strings.Contains(got, "GiB") || !strings.Contains(got, "MB/s") {
		t.Fatalf("got %q", got)
	}
	if !strings.Contains(got, "ETA") {
		t.Fatalf("want ETA in %q", got)
	}
}

func TestFilterSolanaLogLinesPrefersDownload(t *testing.T) {
	raw := []string{
		"noise metrics datapoint foo=1",
		`[INFO solana_file_download] downloaded 1000 bytes 1.0% 1000000.0 bytes/s`,
		"more noise",
		`[WARN agave_validator::bootstrap] The snapshot download is too slow`,
	}
	out := filterSolanaLogLines(raw, 10)
	if len(out) != 2 {
		t.Fatalf("want 2 interesting lines, got %d %v", len(out), out)
	}
	if !strings.Contains(out[0], "downloaded") || !strings.Contains(out[1], "too slow") {
		t.Fatalf("out=%v", out)
	}
}

func TestSolanaLogTailFromFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "solana-mainnet.log")
	body := strings.Repeat("noise line\n", 20)
	body += `[INFO] downloaded 2000000000 bytes 10.0% 20000000.0 bytes/s` + "\n"
	if err := os.WriteFile(path, []byte(body), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg := Config{Env: "mainnet", DataDir: dir, NodeService: "solana-mainnet"}
	tail := solanaLogTail(cfg, 20)
	if len(tail) == 0 || !strings.Contains(tail[len(tail)-1], "10.0%") {
		t.Fatalf("tail=%v", tail)
	}
	detail := solanaWarmupDetail(cfg, "fallback")
	if !strings.Contains(detail, "10.0%") {
		t.Fatalf("detail=%q", detail)
	}
}

func TestBuildStartStepWarmupDetail(t *testing.T) {
	step := buildStartStep(nodeLifecycleInput{
		Network: "solana", Env: "mainnet",
		NodeActive: true, RPCOK: false,
		WarmupDetail: "snapshot download 12.0% · 13.0/110 GiB · 18 MB/s",
	}, networkLifecycleProfile{}, "skipped")
	detail, _ := step["detail"].(string)
	if !strings.Contains(detail, "12.0%") {
		t.Fatalf("detail=%q", detail)
	}
	if step["status"] != "active" {
		t.Fatalf("status=%v", step["status"])
	}
}
