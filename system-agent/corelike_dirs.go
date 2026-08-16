package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
)

// coreLikeNestDirName — subdirectory Core creates under datadir= for this network/env.
// Litecoin testnet=1 → testnet4 (not testnet3). Dash/BCH → testnet3.
func coreLikeNestDirName(network, env string) string {
	net := strings.ToLower(strings.TrimSpace(network))
	switch normalizeEnvName(env) {
	case "regtest":
		return "regtest"
	case "signet":
		return "signet"
	case "testnet4":
		return "testnet4"
	case "testnet", "testnet3":
		switch net {
		case "ltc", "litecoin":
			return "testnet4"
		default:
			return "testnet3"
		}
	default:
		return ""
	}
}

// coreLikeChainDataDir — final on-disk chain dir Core will use (may differ from profile
// DataPath when an older provision used /data/ltc/testnet but litecoind wants testnet4).
func coreLikeChainDataDir(network, env, dataPath string) string {
	dataPath = strings.TrimRight(strings.TrimSpace(dataPath), "/")
	nest := coreLikeNestDirName(network, env)
	if nest == "" || dataPath == "" {
		return dataPath
	}
	parent := bitcoinCoreDatadirSetting(dataPath, env)
	if parent == "" {
		parent = dataPath
	}
	return filepath.Join(parent, nest)
}

// ensureCoreLikeDataDirs — mkdir + chown nodeop for etc, profile data, Core parent, and
// the real nest dir (e.g. /data/ltc/testnet4). Called before litecoind/dashd start so
// Update alone self-heals Permission denied on nest create without SSH.
func ensureCoreLikeDataDirs(cfg Config) error {
	prof := LookupNetworkProfile(cfg.Network, cfg.Env)
	etc := strings.TrimSpace(cfg.EtcDir)
	if etc == "" {
		etc = prof.EtcPath
	}
	data := strings.TrimSpace(cfg.DataDir)
	if data == "" {
		data = prof.DataPath
	}
	parent := bitcoinCoreDatadirSetting(data, cfg.Env)
	chain := coreLikeChainDataDir(cfg.Network, cfg.Env, data)

	seen := map[string]bool{}
	var paths []string
	for _, d := range []string{etc, data, parent, chain} {
		d = strings.TrimRight(strings.TrimSpace(d), "/")
		if d == "" || seen[d] {
			continue
		}
		seen[d] = true
		paths = append(paths, d)
		if err := os.MkdirAll(d, 0o755); err != nil {
			return fmt.Errorf("mkdir %s: %w", d, err)
		}
	}
	if len(paths) > 0 {
		args := append([]string{"-R", "nodeop:nodeop"}, paths...)
		_ = exec.Command("chown", args...).Run()
	}
	return nil
}
