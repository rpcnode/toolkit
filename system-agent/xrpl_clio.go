package main

import (
	"fmt"
	"os/exec"
	"strings"
)

func xrplClioHTTPPort(env string) int {
	if normalizeEnvName(env) == "testnet" {
		return 51234
	}

	return 51233
}

func xrplGRPCPort(env string) int {
	if normalizeEnvName(env) == "testnet" {
		return 51252
	}

	return 51251
}

func xrplWSPublicPort(env string) int {
	if normalizeEnvName(env) == "testnet" {
		return 6008
	}

	return 6005
}

func xrplClioUnitName(env string) string {
	return fmt.Sprintf("xrpl-clio-%s.service", normalizeEnvName(env))
}

func startXRPLClioStack(cfg Config) error {
	if _, err := exec.LookPath("systemctl"); err != nil {
		return nil
	}

	if provisionLockPending(cfg.Network, cfg.Env) || removeJobPending(cfg.Network, cfg.Env) {
		return nil
	}

	_ = exec.Command("pkill", "-9", "-x", "iotune").Run()

	clio := xrplClioUnitName(cfg.Env)
	if !fileExists("/etc/systemd/system/"+clio) && !fileExists("/lib/systemd/system/scylla-server.service") {
		return nil
	}

	_ = exec.Command("systemctl", "daemon-reload").Run()
	if fileExists("/lib/systemd/system/scylla-server.service") || fileExists("/etc/systemd/system/scylla-server.service") {
		_ = exec.Command("systemctl", "enable", "scylla-server.service").Run()
		if out, err := exec.Command("systemctl", "start", "scylla-server.service").CombinedOutput(); err != nil {
			return fmt.Errorf("start scylla-server: %v (%s)", err, strings.TrimSpace(string(out)))
		}
	}

	if !fileExists("/etc/systemd/system/" + clio) {
		return nil
	}

	_ = exec.Command("systemctl", "reset-failed", clio).Run()
	_ = exec.Command("systemctl", "enable", clio).Run()
	if out, err := exec.Command("systemctl", "start", clio).CombinedOutput(); err != nil {
		return fmt.Errorf("start %s: %v (%s)", clio, err, strings.TrimSpace(string(out)))
	}

	return nil
}
