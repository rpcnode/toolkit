package main

import (
	"path/filepath"
	"testing"
)

func TestRecommendSolanaDiskLayout_Single(t *testing.T) {
	mounts := []HostMount{
		{Target: "/data", AvailBytes: 2e12, Preferred: true, DiskName: "nvme0n1"},
	}
	plan := recommendSolanaDiskLayout(mounts, "mainnet")
	if plan.Strategy != "single" {
		t.Fatalf("strategy=%s", plan.Strategy)
	}
	if plan.LedgerDir != "/data/solana/mainnet/ledger" {
		t.Fatalf("ledger=%s", plan.LedgerDir)
	}
	if plan.LedgerMount != plan.AccountsMount || plan.AccountsMount != plan.SnapshotsMount {
		t.Fatalf("single disk should share one mount: %+v", plan)
	}
	if plan.AccountsDir == plan.SnapshotsDir {
		t.Fatalf("accounts and snapshots must be distinct dirs even on one mount")
	}
}

func TestRecommendSolanaDiskLayout_JBOD2(t *testing.T) {
	rotaFalse := false
	mounts := []HostMount{
		{Target: "/mnt/nvme0", AvailBytes: 3e12, Preferred: true, DiskName: "nvme0n1", Rota: &rotaFalse, Tran: "nvme"},
		{Target: "/mnt/nvme1", AvailBytes: 2e12, Preferred: true, DiskName: "nvme1n1", Rota: &rotaFalse, Tran: "nvme"},
	}
	plan := recommendSolanaDiskLayout(mounts, "mainnet")
	if plan.Strategy != "jbod_2" {
		t.Fatalf("strategy=%s", plan.Strategy)
	}
	if plan.LedgerMount == plan.AccountsMount {
		t.Fatalf("ledger and accounts must differ: %+v", plan)
	}
	if plan.SnapshotsMount != plan.AccountsMount {
		t.Fatalf("snapshots should share accounts disk on jbod_2")
	}
	if plan.LedgerDir != filepath.Join(plan.LedgerMount, "solana/mainnet/ledger") {
		t.Fatalf("ledger_dir=%s", plan.LedgerDir)
	}
}

func TestRecommendSolanaDiskLayout_JBOD3(t *testing.T) {
	mounts := []HostMount{
		{Target: "/mnt/a", AvailBytes: 4e12, Preferred: true, DiskName: "nvme0n1"},
		{Target: "/mnt/b", AvailBytes: 3e12, Preferred: true, DiskName: "nvme1n1"},
		{Target: "/mnt/c", AvailBytes: 2e12, Preferred: true, DiskName: "nvme2n1"},
	}
	plan := recommendSolanaDiskLayout(mounts, "testnet")
	if plan.Strategy != "jbod_3" {
		t.Fatalf("strategy=%s", plan.Strategy)
	}
	if plan.LedgerMount == plan.AccountsMount || plan.AccountsMount == plan.SnapshotsMount {
		t.Fatalf("all three mounts should differ: %+v", plan)
	}
}

func TestRecommendSolanaDiskLayout_SameDiskNotFakeJBOD(t *testing.T) {
	mounts := []HostMount{
		{Target: "/", AvailBytes: 1e12, Preferred: true, DiskName: "nvme0n1"},
		{Target: "/data", AvailBytes: 8e11, Preferred: true, DiskName: "nvme0n1"},
	}
	plan := recommendSolanaDiskLayout(mounts, "mainnet")
	if plan.Strategy != "single" {
		t.Fatalf("same underlying disk must stay single, got %s", plan.Strategy)
	}
}

func TestResolveSolanaDiskDirs_DefaultsAndOverrides(t *testing.T) {
	req := nodeProvisionRequest{}
	l, a, s := resolveSolanaDiskDirs(req, "/data/solana/mainnet", "mainnet")
	if l != "/data/solana/mainnet/ledger" || a != "/data/solana/mainnet/accounts" || s != "/data/solana/mainnet/snapshots" {
		t.Fatalf("defaults: %s %s %s", l, a, s)
	}
	req = nodeProvisionRequest{
		DiskLayout: &solanaDiskLayoutIn{
			LedgerMount:    "/mnt/nvme0",
			AccountsMount:  "/mnt/nvme1",
			SnapshotsMount: "/mnt/nvme2",
		},
	}
	l, a, s = resolveSolanaDiskDirs(req, "/data/solana/mainnet", "mainnet")
	if l != "/mnt/nvme0/solana/mainnet/ledger" {
		t.Fatalf("ledger=%s", l)
	}
	if a != "/mnt/nvme1/solana/mainnet/accounts" {
		t.Fatalf("accounts=%s", a)
	}
	if s != "/mnt/nvme2/solana/mainnet/snapshots" {
		t.Fatalf("snapshots=%s", s)
	}
}

func TestValidateSolanaDataPath(t *testing.T) {
	if err := validateSolanaDataPath("/data/solana/mainnet/ledger"); err != nil {
		t.Fatal(err)
	}
	if err := validateSolanaDataPath("/etc/passwd"); err == nil {
		t.Fatal("expected refuse /etc")
	}
	if err := validateSolanaDataPath("relative"); err == nil {
		t.Fatal("expected absolute")
	}
}
