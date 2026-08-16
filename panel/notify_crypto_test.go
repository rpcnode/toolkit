package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestNotifySecretEncryptDecrypt(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "panel.db")
	t.Setenv(notifyKeyEnv, "")
	t.Setenv("RPCNODE_NOTIFY_KEY_FILE", filepath.Join(dir, "panel.notify.key"))

	// reset package cache between tests
	notifyKeyMu.Lock()
	notifyKeyCache = nil
	notifyKeySrc = ""
	notifyKeyMu.Unlock()

	plain := "123456:ABC-DEF_secret-token"
	enc, err := encryptNotifySecret(dbPath, plain)
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}
	if enc == "" || enc == plain {
		t.Fatalf("expected ciphertext, got %q", enc)
	}
	got, err := decryptNotifySecret(dbPath, enc)
	if err != nil {
		t.Fatalf("decrypt: %v", err)
	}
	if got != plain {
		t.Fatalf("got %q want %q", got, plain)
	}
	if _, err := os.Stat(notifyKeyPath(dbPath)); err != nil {
		t.Fatalf("key file missing: %v", err)
	}
	if tokenHint(plain) != "oken" && tokenHint(plain) != plain[len(plain)-4:] {
		t.Fatalf("hint=%q", tokenHint(plain))
	}
}

func TestNotifySecretWrongKeyFails(t *testing.T) {
	dir := t.TempDir()
	dbPath := filepath.Join(dir, "panel.db")
	keyFile := filepath.Join(dir, "panel.notify.key")
	t.Setenv(notifyKeyEnv, "")
	t.Setenv("RPCNODE_NOTIFY_KEY_FILE", keyFile)

	notifyKeyMu.Lock()
	notifyKeyCache = nil
	notifyKeySrc = ""
	notifyKeyMu.Unlock()

	enc, err := encryptNotifySecret(dbPath, "tok-abc")
	if err != nil {
		t.Fatalf("encrypt: %v", err)
	}

	// Replace key file and clear cache.
	_ = os.WriteFile(keyFile, []byte("0123456789abcdef0123456789abcdef"), 0o600)
	notifyKeyMu.Lock()
	notifyKeyCache = nil
	notifyKeySrc = ""
	notifyKeyMu.Unlock()

	if _, err := decryptNotifySecret(dbPath, enc); err == nil {
		t.Fatal("expected decrypt failure with rotated key")
	}
}
