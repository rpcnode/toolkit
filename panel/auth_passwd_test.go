package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestSetPasswordPreservesOtherUsers(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "panel.htpasswd")
	auth := NewPanelAuth(path)
	if err := auth.SetPassword("admin", "password1"); err != nil {
		t.Fatal(err)
	}
	if err := auth.SetPassword("ops", "password2"); err != nil {
		t.Fatal(err)
	}
	if err := auth.SetPassword("admin", "password3x"); err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	s := string(b)
	if !auth.verify("admin", "password3x") {
		t.Fatalf("admin password not updated; file=%q", s)
	}
	if !auth.verify("ops", "password2") {
		t.Fatalf("ops user lost; file=%q", s)
	}
	if auth.verify("admin", "password1") {
		t.Fatal("old admin password still works")
	}
}
