package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestRemoveJobShouldResume(t *testing.T) {
	if !removeJobShouldResume("deleting", "doge", "mainnet") {
		t.Fatal("deleting must resume")
	}
	if !removeJobShouldResume("started", "cardano", "mainnet") {
		t.Fatal("started must resume")
	}
	if removeJobShouldResume("completed", "doge", "mainnet") {
		t.Fatal("completed must not resume")
	}
	// clearRemoveJobOnProvision must supersede completed too (re-add after wipe).
	if removeJobShouldResume("aborted_heal", "stellar", "testnet") {
		t.Fatal("aborted_heal must not resume")
	}
	if removeJobShouldResume("superseded", "doge", "mainnet") {
		t.Fatal("superseded must not resume")
	}
}

func TestRemoveDirIfEmpty(t *testing.T) {
	root := t.TempDir()
	parent := filepath.Join(root, "doge")
	child := filepath.Join(parent, "mainnet")
	if err := os.MkdirAll(child, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := removeDirIfEmpty(parent); err == nil {
		t.Fatal("parent with child must not remove")
	}
	if err := os.Remove(child); err != nil {
		t.Fatal(err)
	}
	if err := removeDirIfEmpty(parent); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(parent); !os.IsNotExist(err) {
		t.Fatal("parent should be gone")
	}
}

func TestForceWriteRemoveJobReplacesSuperseded(t *testing.T) {
	prev := removeJobsDir
	removeJobsDir = t.TempDir()
	t.Cleanup(func() { removeJobsDir = prev })

	writeRemoveJob("sui", "mainnet", "superseded", "cleared by provision", nil)
	if !removeJobIsSuperseded("sui", "mainnet") {
		t.Fatal("expected superseded")
	}
	// In-flight async must still not clobber.
	writeRemoveJobWithWipe("sui", "mainnet", "deleting", "", nil, true)
	if removeJobStatus("sui", "mainnet") != "superseded" {
		t.Fatalf("async must not clobber superseded, got %q", removeJobStatus("sui", "mainnet"))
	}
	// Explicit remove start must replace superseded so leftovers can be wiped.
	forceWriteRemoveJobWithWipe("sui", "mainnet", "started", "", nil, true)
	if removeJobStatus("sui", "mainnet") != "started" {
		t.Fatalf("force start want started, got %q", removeJobStatus("sui", "mainnet"))
	}
	if !removeJobDeleteFiles("sui", "mainnet") {
		t.Fatal("delete_files must be true")
	}
}

func TestResumeHonorsDeleteFilesFlag(t *testing.T) {
	prev := removeJobsDir
	removeJobsDir = t.TempDir()
	t.Cleanup(func() { removeJobsDir = prev })

	writeRemoveJobWithWipe("bch", "regtest", "deleting", "", nil, false)
	if removeJobDeleteFiles("bch", "regtest") {
		t.Fatal("resume must not invent wipe when job says false")
	}
	writeRemoveJobWithWipe("sui", "mainnet", "started", "", nil, true)
	if !removeJobDeleteFiles("sui", "mainnet") {
		t.Fatal("resume must wipe when job says true")
	}
}

func TestWriteRemoveJobDoesNotClobberSuperseded(t *testing.T) {
	prev := removeJobsDir
	removeJobsDir = t.TempDir()
	t.Cleanup(func() { removeJobsDir = prev })

	writeRemoveJob("doge", "regtest", "deleting", "", nil)
	if removeJobStatus("doge", "regtest") != "deleting" {
		t.Fatalf("status=%q", removeJobStatus("doge", "regtest"))
	}
	writeRemoveJob("doge", "regtest", "superseded", "cleared by provision", nil)
	if !removeJobIsSuperseded("doge", "regtest") {
		t.Fatal("expected superseded")
	}
	// In-flight wipe must not resurrect deleting/completed over provision.
	writeRemoveJob("doge", "regtest", "deleting", "", nil)
	if removeJobStatus("doge", "regtest") != "superseded" {
		t.Fatalf("deleting must not clobber superseded, got %q", removeJobStatus("doge", "regtest"))
	}
	writeRemoveJob("doge", "regtest", "completed", "", nil)
	if removeJobStatus("doge", "regtest") != "superseded" {
		t.Fatalf("completed must not clobber superseded, got %q", removeJobStatus("doge", "regtest"))
	}
	writeRemoveJob("doge", "regtest", "wiped", "", nil)
	if removeJobStatus("doge", "regtest") != "superseded" {
		t.Fatalf("wiped must not clobber superseded, got %q", removeJobStatus("doge", "regtest"))
	}
}
