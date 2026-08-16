package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestDiskFreeGBDoesNotCreateMissingPath(t *testing.T) {
	root := t.TempDir()
	missing := filepath.Join(root, "data", "tron", "mainnet")
	if _, err := os.Stat(missing); !os.IsNotExist(err) {
		t.Fatalf("precondition: %v", err)
	}
	avail, _, _ := diskFreeGB(missing)
	if avail <= 0 {
		t.Fatalf("expected positive free space via ancestor, got %d", avail)
	}
	if _, err := os.Stat(missing); !os.IsNotExist(err) {
		t.Fatalf("diskFreeGB must not create %s (err=%v)", missing, err)
	}
}

func TestHostTipConfigSkipsTronDataDir(t *testing.T) {
	t.Setenv("TRON_NETWORK", "")
	t.Setenv("TRON_STATE_DIR", "/var/lib/rpcnode/host")
	t.Setenv("TRON_ENV", "mainnet")
	t.Setenv("RPCNODE_ENV", "mainnet")
	t.Setenv("TRON_DATA", "")
	t.Setenv("TRON_OPT", "")
	t.Setenv("TRON_ETC", "")
	cfg := loadConfig()
	if !cfg.HostTip {
		t.Fatal("expected HostTip")
	}
	if cfg.DataDir == "/data/tron/mainnet" || cfg.EtcDir == "/etc/tron/mainnet" {
		t.Fatalf("tip must not use tron leaf paths: data=%q etc=%q", cfg.DataDir, cfg.EtcDir)
	}
	if cfg.DataDir != "/var/lib/rpcnode/host" {
		t.Fatalf("DataDir=%q", cfg.DataDir)
	}
	if cfg.EtcDir != "/etc/rpcnode" {
		t.Fatalf("EtcDir=%q", cfg.EtcDir)
	}
}
