package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestBeginEndProvisionLock(t *testing.T) {
	prev := provisionLocksDir
	provisionLocksDir = t.TempDir()
	t.Cleanup(func() { provisionLocksDir = prev })

	if err := beginProvisionLock("xrpl", "mainnet"); err != nil {
		t.Fatal(err)
	}

	path := filepath.Join(provisionLocksDir, "xrpl-mainnet.json")
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}

	body := string(b)
	if !strings.Contains(body, `"status":"running"`) && !strings.Contains(body, `"status": "running"`) {
		t.Fatalf("lock body: %s", body)
	}
	if !strings.Contains(body, `"network":"xrpl"`) && !strings.Contains(body, `"network": "xrpl"`) {
		t.Fatalf("lock body: %s", body)
	}

	endProvisionLock("xrpl", "mainnet")
	if fileExists(path) {
		t.Fatal("end must remove lock")
	}
}
