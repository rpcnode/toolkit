package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
)

const (
	notifyKeyFileName = "panel.notify.key"
	notifyKeyEnv      = "RPCNODE_NOTIFY_KEY"
	notifyKeyBytes    = 32
)

var (
	notifyKeyMu    sync.Mutex
	notifyKeyCache []byte
	notifyKeySrc   string // "env" | "file"
)

// notifyDataDir — directory next to panel.db (holds panel.notify.key).
func notifyDataDir(dbPath string) string {
	dbPath = strings.TrimSpace(dbPath)
	if dbPath == "" {
		return "/var/lib/rpcnode"
	}
	return filepath.Dir(dbPath)
}

func notifyKeyPath(dbPath string) string {
	if v := strings.TrimSpace(os.Getenv("RPCNODE_NOTIFY_KEY_FILE")); v != "" {
		return v
	}
	return filepath.Join(notifyDataDir(dbPath), notifyKeyFileName)
}

// loadOrCreateNotifyKey returns a 32-byte AES key. Prefers env, else file (create if missing).
func loadOrCreateNotifyKey(dbPath string) (key []byte, source string, err error) {
	notifyKeyMu.Lock()
	defer notifyKeyMu.Unlock()
	if len(notifyKeyCache) == notifyKeyBytes {
		return append([]byte(nil), notifyKeyCache...), notifyKeySrc, nil
	}

	if env := strings.TrimSpace(os.Getenv(notifyKeyEnv)); env != "" {
		raw, decErr := base64.StdEncoding.DecodeString(env)
		if decErr != nil {
			raw, decErr = base64.RawStdEncoding.DecodeString(env)
		}
		if decErr != nil {
			return nil, "", fmt.Errorf("%s: invalid base64: %w", notifyKeyEnv, decErr)
		}
		if len(raw) != notifyKeyBytes {
			return nil, "", fmt.Errorf("%s: need %d bytes, got %d", notifyKeyEnv, notifyKeyBytes, len(raw))
		}
		notifyKeyCache = append([]byte(nil), raw...)
		notifyKeySrc = "env"
		return append([]byte(nil), notifyKeyCache...), notifyKeySrc, nil
	}

	path := notifyKeyPath(dbPath)
	if b, readErr := os.ReadFile(path); readErr == nil {
		raw := bytesTrimSpace(b)
		if len(raw) == notifyKeyBytes {
			notifyKeyCache = append([]byte(nil), raw...)
			notifyKeySrc = "file"
			return append([]byte(nil), notifyKeyCache...), notifyKeySrc, nil
		}
		// allow base64-encoded file contents
		if dec, decErr := base64.StdEncoding.DecodeString(string(raw)); decErr == nil && len(dec) == notifyKeyBytes {
			notifyKeyCache = dec
			notifyKeySrc = "file"
			return append([]byte(nil), notifyKeyCache...), notifyKeySrc, nil
		}
		return nil, "", fmt.Errorf("notify key file %s: expected %d raw bytes", path, notifyKeyBytes)
	}

	key = make([]byte, notifyKeyBytes)
	if _, err = io.ReadFull(rand.Reader, key); err != nil {
		return nil, "", err
	}
	if err = os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, "", err
	}
	if err = os.WriteFile(path, key, 0o600); err != nil {
		return nil, "", err
	}
	notifyKeyCache = append([]byte(nil), key...)
	notifyKeySrc = "file"
	return append([]byte(nil), key...), notifyKeySrc, nil
}

func bytesTrimSpace(b []byte) []byte {
	return []byte(strings.TrimSpace(string(b)))
}

func encryptNotifySecret(dbPath, plaintext string) (encB64 string, err error) {
	plaintext = strings.TrimSpace(plaintext)
	if plaintext == "" {
		return "", errors.New("empty plaintext")
	}
	key, _, err := loadOrCreateNotifyKey(dbPath)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err = io.ReadFull(rand.Reader, nonce); err != nil {
		return "", err
	}
	out := gcm.Seal(nonce, nonce, []byte(plaintext), nil)
	return base64.StdEncoding.EncodeToString(out), nil
}

func decryptNotifySecret(dbPath, encB64 string) (string, error) {
	encB64 = strings.TrimSpace(encB64)
	if encB64 == "" {
		return "", errors.New("empty ciphertext")
	}
	raw, err := base64.StdEncoding.DecodeString(encB64)
	if err != nil {
		return "", err
	}
	key, _, err := loadOrCreateNotifyKey(dbPath)
	if err != nil {
		return "", err
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return "", err
	}
	ns := gcm.NonceSize()
	if len(raw) < ns {
		return "", errors.New("ciphertext too short")
	}
	plain, err := gcm.Open(nil, raw[:ns], raw[ns:], nil)
	if err != nil {
		return "", fmt.Errorf("decrypt failed (wrong key?): %w", err)
	}
	return string(plain), nil
}

func tokenHint(token string) string {
	token = strings.TrimSpace(token)
	if token == "" {
		return ""
	}
	if len(token) <= 4 {
		return token
	}
	return token[len(token)-4:]
}
