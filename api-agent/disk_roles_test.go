package main

import "testing"

func TestMultiDiskRolesCatalog(t *testing.T) {
	for _, net := range []string{
		"solana", "ethereum", "bsc", "arb", "base", "optimism", "tron", "ton",
		"sui", "aptos", "avalanche", "bitcoin", "ltc", "dash", "robinhood",
	} {
		roles := multiDiskRolesForNetwork(net)
		if len(roles) < 2 {
			t.Fatalf("%s: want ≥2 roles, got %d", net, len(roles))
		}
	}
	if len(multiDiskRolesForNetwork("polygon")) != 0 {
		t.Fatal("polygon must not invent half-network roles in agent")
	}
}

func TestRecommendMultiDisk_AptosJBOD2(t *testing.T) {
	mounts := []HostMount{
		{Target: "/nvme0", Preferred: true, AvailBytes: 2e12, SizeBytes: 2e12, DiskName: "nvme0n1"},
		{Target: "/nvme1", Preferred: true, AvailBytes: 2e12, SizeBytes: 2e12, DiskName: "nvme1n1"},
		{Target: "/", Preferred: true, AvailBytes: 50e9, SizeBytes: 100e9, DiskName: "sda"},
	}
	plan := recommendMultiDiskLayout("aptos", "mainnet", mounts)
	if plan.Strategy != "jbod_2" {
		t.Fatalf("strategy=%s", plan.Strategy)
	}
	if len(plan.Roles) != 2 {
		t.Fatalf("roles=%d", len(plan.Roles))
	}
	if plan.Roles[0].ID != "state" || plan.Roles[1].ID != "index" {
		t.Fatalf("roles=%+v", plan.Roles)
	}
	if plan.Roles[0].Mount == "/" || plan.Roles[1].Mount == "/" {
		t.Fatalf("OS root used for data: %+v", plan.Roles)
	}
	if plan.StateDir == "" || plan.IndexDir == "" {
		t.Fatalf("compat fields empty: %+v", plan)
	}
}

func TestRecommendMultiDisk_EthereumSingle(t *testing.T) {
	mounts := []HostMount{
		{Target: "/data", Preferred: true, AvailBytes: 4e12, SizeBytes: 4e12, DiskName: "nvme0n1"},
	}
	plan := recommendMultiDiskLayout("ethereum", "mainnet", mounts)
	if plan.Strategy != "single" {
		t.Fatalf("strategy=%s", plan.Strategy)
	}
	if len(plan.Roles) != 2 || plan.Roles[0].ID != "execution" {
		t.Fatalf("roles=%+v", plan.Roles)
	}
	if plan.Roles[0].Dir != "/data/ethereum/mainnet/geth" {
		t.Fatalf("execution dir=%s", plan.Roles[0].Dir)
	}
}

func TestFilterDataMountsExcludesOS(t *testing.T) {
	mounts := []HostMount{
		{Target: "/", Preferred: true, AvailBytes: 80e9, SizeBytes: 120e9, DiskName: "sda"},
		{Target: "/data", Preferred: true, AvailBytes: 3e12, SizeBytes: 4e12, DiskName: "nvme0n1"},
		{Target: "/mnt/data2", Preferred: true, AvailBytes: 3e12, SizeBytes: 4e12, DiskName: "nvme1n1"},
	}
	out := filterDataMountCandidates(mounts)
	for _, m := range out {
		if m.Target == "/" {
			t.Fatalf("root still a candidate: %+v", out)
		}
	}
	if len(out) < 2 {
		t.Fatalf("want ≥2 data mounts, got %+v", out)
	}
}

func TestRecommendMultiDisk_DataNvmeBindMounts(t *testing.T) {
	mounts := []HostMount{
		{Target: "/", Preferred: true, AvailBytes: 437e9, SizeBytes: 463e9, DiskName: "nvme0n1", Tran: "nvme"},
		{Target: "/data/nvme2", Preferred: true, AvailBytes: 3.5e12, SizeBytes: 3.5e12, DiskName: "nvme2n1", Tran: "nvme"},
		{Target: "/data/nvme3", Preferred: true, AvailBytes: 3.5e12, SizeBytes: 3.5e12, DiskName: "nvme3n1", Tran: "nvme"},
	}
	plan := recommendMultiDiskLayout("tron", "mainnet", mounts)
	if plan.Strategy != "jbod_2" {
		t.Fatalf("strategy=%s notes=%v", plan.Strategy, plan.Notes)
	}
	for _, r := range plan.Roles {
		if r.Mount == "/" || r.Mount == "/data" {
			t.Fatalf("data role on OS mount: %+v", r)
		}
		if r.Mount != "/data/nvme2" && r.Mount != "/data/nvme3" {
			t.Fatalf("unexpected mount %s", r.Mount)
		}
	}
}
