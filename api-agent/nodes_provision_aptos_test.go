package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestResolveAptosDiskDirsFallback(t *testing.T) {
	req := nodeProvisionRequest{Env: "mainnet"}
	state, index := resolveAptosDiskDirs(req, "/data/aptos/mainnet", "mainnet")
	if state != "/data/aptos/mainnet/db" || index != "/data/aptos/mainnet/index" {
		t.Fatalf("fallback state=%s index=%s", state, index)
	}
}

func TestResolveAptosDiskDirsRoles(t *testing.T) {
	req := nodeProvisionRequest{
		Env: "mainnet",
		DiskLayout: &solanaDiskLayoutIn{
			Strategy: "jbod_2",
			Roles: map[string]diskRoleIn{
				"state": {Mount: "/nvme0"},
				"index": {Dir: "/nvme1/aptos/mainnet/index"},
			},
		},
	}
	state, index := resolveAptosDiskDirs(req, "/data/aptos/mainnet", "mainnet")
	wantState := filepath.Clean("/nvme0/aptos/mainnet/db")
	wantIndex := filepath.Clean("/nvme1/aptos/mainnet/index")
	if state != wantState || index != wantIndex {
		t.Fatalf("roles state=%s index=%s want %s / %s", state, index, wantState, wantIndex)
	}
}

func TestAptosReleaseTags(t *testing.T) {
	if aptosReleaseTag("mainnet") != "aptos-node-v1.48.6-hotfix" {
		t.Fatalf("mainnet tag: %s", aptosReleaseTag("mainnet"))
	}
	if aptosReleaseTag("testnet") != "aptos-node-v1.48.6-rc" {
		t.Fatalf("testnet tag: %s", aptosReleaseTag("testnet"))
	}
	if aptosReleaseAssetName() != "aptos-node-performance-ubuntu-22.04.tgz" {
		t.Fatalf("asset: %s", aptosReleaseAssetName())
	}
}

func TestAptosAdminServicePort(t *testing.T) {
	if aptosAdminServicePort(9101) != 9111 {
		t.Fatalf("mainnet admin: %d", aptosAdminServicePort(9101))
	}
	if aptosAdminServicePort(9102) != 9112 {
		t.Fatalf("testnet admin: %d", aptosAdminServicePort(9102))
	}
	if aptosBackupServicePort(6180) != 6186 || aptosBackupServicePort(6182) != 6188 {
		t.Fatalf("backup ports: %d %d", aptosBackupServicePort(6180), aptosBackupServicePort(6182))
	}
}

func TestWriteAptosFullnodeYAMLPinsAdminPort(t *testing.T) {
	dir := t.TempDir()
	prof := networkPortProfile{Env: "testnet", EtcPath: dir, DataPath: filepath.Join(dir, "data"), NodeHTTP: 8081, P2P: 6182}
	req := nodeProvisionRequest{Env: "testnet", NodeHTTPPort: 8081, P2PPort: 6182}
	path, err := writeAptosFullnodeYAML(prof, req, filepath.Join(dir, "genesis.blob"), filepath.Join(dir, "waypoint.txt"), filepath.Join(dir, "db"), 9102, 9112)
	if err != nil {
		t.Fatal(err)
	}
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	body := string(b)
	if !strings.Contains(body, "port: 9102") || !strings.Contains(body, "admin_service:") || !strings.Contains(body, "port: 9112") {
		t.Fatalf("yaml missing inspection/admin pins:\n%s", body)
	}
	if !strings.Contains(body, `backup_service_address: "127.0.0.1:6188"`) {
		t.Fatalf("yaml missing backup pin:\n%s", body)
	}
}
