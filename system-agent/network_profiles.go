package main

import (
	"fmt"
	"sort"
	"strings"
)

// Package-level network catalog for the host system-agent.
//
// # How to add a new network (or env)
//
//  1. Append one NetworkProfile literal to builtinNetworkProfiles() below
//     (or call RegisterNetworkProfile from a sibling file's init).
//  2. Fill Network, Env, DisplayName, SnapshotPolicy, AutoSnapshot, AutoStartNode,
//     default ports/URL, ExtraSteps, ServicePrefix, NodeBinaryHint.
//  3. ExtraSteps lists only steps after install and before start (usually StepSnapshot).
//     Common base is always: ports → install → [ExtraSteps…] → start → run.
//  4. Rebuild system-agent (+ publish when ready). Do not add switch/case on
//     env/network names in collect.go, lifecycle.go, or pipeline.go.
//
// Runtime gating uses the minimal networkLifecycleProfile
// (IncludeSnapshot / SnapshotRequired / AutoSnapshot / AutoStartNode) consumed by
// pipeline.go and applySequentialGate. This file is the static catalog;
// resolveLifecycleProfile maps a catalog entry onto that runtime shape.

const (
	// DefaultNetwork is used when TRON_NETWORK / input network is empty.
	DefaultNetwork = "tron"
	// DefaultEnv is used when network is DefaultNetwork and env is empty.
	DefaultEnv = "mainnet"

	// StepSnapshot is the ExtraSteps / lifecycle.steps[].id for chain-data bootstrap.
	StepSnapshot = "snapshot"
)

// SnapshotPolicy is the static intent for chain-data bootstrap on a profile.
type SnapshotPolicy int

const (
	// SnapshotNever omits the snapshot step (unless a previous run is still in flight).
	SnapshotNever SnapshotPolicy = iota
	// SnapshotOptional includes snapshot when enabled or already busy/failed/ready.
	SnapshotOptional
	// SnapshotRequired always includes snapshot unless TRON_SNAPSHOT_ENABLED disables it.
	SnapshotRequired
)

// NetworkProfile is static metadata for one network/env pair.
// Safe to add 100+ entries — lookup is O(1) by "network/env".
type NetworkProfile struct {
	ID          string // "tron/mainnet" (filled by RegisterNetworkProfile if empty)
	Network     string // "tron" | "bitcoin"
	Env         string // "mainnet"
	DisplayName string

	SnapshotPolicy     SnapshotPolicy
	AutoSnapshot       bool // pipeline may start snapshot
	AutoStartNode      bool // pipeline may start node after prior steps
	DefaultSnapshotURL string

	DefaultPublicPort int
	DefaultAgentPort  int
	DefaultP2PPort    int
	DefaultNodeHTTP   int // java-tron HTTP or bitcoind JSON-RPC

	// Bitcoin Core conf / product metadata (ignored for TRON).
	ChainFlag   string // e.g. chain=testnet4, signet=1
	WatchSlug   string
	ZMQRawBlock int
	ZMQRawTx    int
	DiskHintGiB float64 // IBD disk plan; agent gate uses ~20% free

	OptPath  string
	EtcPath  string
	DataPath string

	// ExtraSteps are inserted after install and before start (e.g. StepSnapshot).
	ExtraSteps []string

	ServicePrefix  string // "tron" → tron-mainnet.service; "bitcoin" → bitcoin-mainnet.service
	NodeBinaryHint string // "java-tron" | "bitcoind"
}

var networkProfiles = map[string]NetworkProfile{}

func init() {
	for _, p := range builtinNetworkProfiles() {
		RegisterNetworkProfile(p)
	}
}

func profileKey(network, env string) string {
	return strings.ToLower(strings.TrimSpace(network)) + "/" + strings.ToLower(strings.TrimSpace(env))
}

// RegisterNetworkProfile adds or replaces a catalog entry.
// Prefer builtinNetworkProfiles() for shipped networks; use this from tests or extensions.
func RegisterNetworkProfile(p NetworkProfile) {
	p.Network = strings.ToLower(strings.TrimSpace(p.Network))
	p.Env = strings.ToLower(strings.TrimSpace(p.Env))
	if p.Network == "" {
		return
	}
	if p.ID == "" {
		p.ID = profileKey(p.Network, p.Env)
	}
	if p.DisplayName == "" {
		p.DisplayName = p.Network + "/" + p.Env
	}
	if p.ServicePrefix == "" {
		p.ServicePrefix = p.Network
	}
	// Copy ExtraSteps so callers cannot mutate the registry via the slice header.
	if len(p.ExtraSteps) > 0 {
		p.ExtraSteps = append([]string(nil), p.ExtraSteps...)
	}
	networkProfiles[profileKey(p.Network, p.Env)] = p
}

// builtinNetworkProfiles returns the shipped catalog.
// Add new networks here — one struct literal per network/env.
//
// Port defaults MUST match api-agent canonicalPorts() (nodes_provision.go).
// Legacy Docker used agent :8093; provisioned env agents use 3919x.
func builtinNetworkProfiles() []NetworkProfile {
	// Public TRON FullNode snapshots (not secrets). Override via TRON_SNAPSHOT_URL.
	// Nile MUST NOT reuse the mainnet mirror — see deploy/nodes/tron/DESIGN.md.
	const tronSnapURL = "http://34.86.86.229/backup20260808/FullNode_output-directory.tgz"
	const nileSnapURL = "https://snapshots.nileex.io/backup20260809/FullNode_output-directory.tgz"

	return []NetworkProfile{
		{
			Network:            "tron",
			Env:                "mainnet",
			DisplayName:        "TRON Mainnet",
			SnapshotPolicy:     SnapshotRequired,
			AutoSnapshot:       true,
			AutoStartNode:      true,
			DefaultSnapshotURL: tronSnapURL,
			DefaultPublicPort:  39090,
			DefaultAgentPort:   39190,
			DefaultP2PPort:     18888,
			DefaultNodeHTTP:    18090,
			DiskHintGiB:        1024,
			OptPath:            "/opt/tron/mainnet",
			EtcPath:            "/etc/tron/mainnet",
			DataPath:           "/data/tron/mainnet",
			ExtraSteps:         []string{StepSnapshot},
			ServicePrefix:      "tron",
			NodeBinaryHint:     "java-tron",
		},
		{
			Network:            "tron",
			Env:                "nile",
			DisplayName:        "TRON Nile",
			SnapshotPolicy:     SnapshotRequired,
			AutoSnapshot:       true,
			AutoStartNode:      true,
			DefaultSnapshotURL: nileSnapURL,
			DefaultPublicPort:  39091,
			DefaultAgentPort:   39191,
			DefaultP2PPort:     18889,
			DefaultNodeHTTP:    18091,
			DiskHintGiB:        256,
			OptPath:            "/opt/tron/nile",
			EtcPath:            "/etc/tron/nile",
			DataPath:           "/data/tron/nile",
			ExtraSteps:         []string{StepSnapshot},
			ServicePrefix:      "tron",
			NodeBinaryHint:     "java-tron",
		},
		{
			Network:           "tron",
			Env:               "shasta",
			DisplayName:       "TRON Shasta",
			SnapshotPolicy:    SnapshotOptional,
			AutoSnapshot:      true,
			AutoStartNode:     true,
			DefaultPublicPort: 39092,
			DefaultAgentPort:  39192,
			DefaultP2PPort:    18890,
			DefaultNodeHTTP:   18092,
			DiskHintGiB:       256,
			OptPath:           "/opt/tron/shasta",
			EtcPath:           "/etc/tron/shasta",
			DataPath:          "/data/tron/shasta",
			ExtraSteps:        []string{StepSnapshot},
			ServicePrefix:     "tron",
			NodeBinaryHint:    "java-tron",
		},
		// Bitcoin — IBD only (no TRON snapshot). Ports MUST NOT collide with TRON 3909x/3919x.
		// Canonical: deploy/nodes/bitcoin/DESIGN.md §5 + former bitcoin/toolkit/profiles/networks.yaml.
		{
			Network:           "bitcoin",
			Env:               "mainnet",
			DisplayName:       "Bitcoin Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39290,
			DefaultAgentPort:  39390,
			DefaultP2PPort:    8333,
			DefaultNodeHTTP:   8332,
			WatchSlug:         "bitcoin",
			ZMQRawBlock:       28332,
			ZMQRawTx:          28333,
			DiskHintGiB:       1024,
			OptPath:           "/opt/bitcoin/mainnet",
			EtcPath:           "/etc/bitcoin/mainnet",
			DataPath:          "/data/bitcoin/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "bitcoin",
			NodeBinaryHint:    "bitcoind",
		},
		{
			Network:           "bitcoin",
			Env:               "testnet4",
			DisplayName:       "Bitcoin Testnet4",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39291,
			DefaultAgentPort:  39391,
			DefaultP2PPort:    18333,
			DefaultNodeHTTP:   18332,
			ChainFlag:         "chain=testnet4",
			WatchSlug:         "bitcoin-testnet4",
			ZMQRawBlock:       28342,
			ZMQRawTx:          28343,
			DiskHintGiB:       128,
			OptPath:           "/opt/bitcoin/testnet4",
			EtcPath:           "/etc/bitcoin/testnet4",
			DataPath:          "/data/bitcoin/testnet4",
			ExtraSteps:        nil,
			ServicePrefix:     "bitcoin",
			NodeBinaryHint:    "bitcoind",
		},
		{
			Network:           "bitcoin",
			Env:               "signet",
			DisplayName:       "Bitcoin Signet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39292,
			DefaultAgentPort:  39392,
			DefaultP2PPort:    38333,
			DefaultNodeHTTP:   38332,
			ChainFlag:         "signet=1",
			WatchSlug:         "bitcoin-signet",
			ZMQRawBlock:       28352,
			ZMQRawTx:          28353,
			DiskHintGiB:       64,
			OptPath:           "/opt/bitcoin/signet",
			EtcPath:           "/etc/bitcoin/signet",
			DataPath:          "/data/bitcoin/signet",
			ExtraSteps:        nil,
			ServicePrefix:     "bitcoin",
			NodeBinaryHint:    "bitcoind",
		},
		{
			Network:           "bitcoin",
			Env:               "regtest",
			DisplayName:       "Bitcoin Regtest",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39293,
			DefaultAgentPort:  39393,
			DefaultP2PPort:    18444,
			DefaultNodeHTTP:   18443,
			ChainFlag:         "regtest=1",
			WatchSlug:         "bitcoin-regtest",
			ZMQRawBlock:       28362,
			ZMQRawTx:          28363,
			DiskHintGiB:       8,
			OptPath:           "/opt/bitcoin/regtest",
			EtcPath:           "/etc/bitcoin/regtest",
			DataPath:          "/data/bitcoin/regtest",
			ExtraSteps:        nil,
			ServicePrefix:     "bitcoin",
			NodeBinaryHint:    "bitcoind",
		},
		// Solana — Agave catch-up (no TRON snapshot). Ports MUST NOT collide with TRON/Bitcoin.
		// Canonical: deploy/nodes/solana/DESIGN.md §5.
		{
			Network:           "solana",
			Env:               "mainnet",
			DisplayName:       "Solana Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39490,
			DefaultAgentPort:  39590,
			DefaultP2PPort:    8000,
			DefaultNodeHTTP:   8899,
			ChainFlag:         "mainnet-beta",
			WatchSlug:         "solana",
			DiskHintGiB:       2048,
			OptPath:           "/opt/solana/mainnet",
			EtcPath:           "/etc/solana/mainnet",
			DataPath:          "/data/solana/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "solana",
			NodeBinaryHint:    "agave-validator",
		},
		{
			Network:           "solana",
			Env:               "testnet",
			DisplayName:       "Solana Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39491,
			DefaultAgentPort:  39591,
			DefaultP2PPort:    8100,
			DefaultNodeHTTP:   8891,
			ChainFlag:         "testnet",
			WatchSlug:         "solana-testnet",
			DiskHintGiB:       1024,
			OptPath:           "/opt/solana/testnet",
			EtcPath:           "/etc/solana/testnet",
			DataPath:          "/data/solana/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "solana",
			NodeBinaryHint:    "agave-validator",
		},
		{
			Network:           "solana",
			Env:               "devnet",
			DisplayName:       "Solana Devnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39492,
			DefaultAgentPort:  39592,
			DefaultP2PPort:    8200,
			DefaultNodeHTTP:   8893,
			ChainFlag:         "devnet",
			WatchSlug:         "solana-devnet",
			DiskHintGiB:       512,
			OptPath:           "/opt/solana/devnet",
			EtcPath:           "/etc/solana/devnet",
			DataPath:          "/data/solana/devnet",
			ExtraSteps:        nil,
			ServicePrefix:     "solana",
			NodeBinaryHint:    "agave-validator",
		},
		{
			Network:           "solana",
			Env:               "localnet",
			DisplayName:       "Solana Localnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39493,
			DefaultAgentPort:  39593,
			DefaultP2PPort:    0,
			DefaultNodeHTTP:   18899,
			ChainFlag:         "localnet",
			WatchSlug:         "solana-localnet",
			DiskHintGiB:       8,
			OptPath:           "/opt/solana/localnet",
			EtcPath:           "/etc/solana/localnet",
			DataPath:          "/data/solana/localnet",
			ExtraSteps:        nil,
			ServicePrefix:     "solana",
			NodeBinaryHint:    "solana-test-validator",
		},
		// Ethereum — Geth + Lighthouse EL/CL (no TRON snapshot). Ports MUST NOT collide with TRON/Bitcoin/Solana.
		// Canonical: deploy/nodes/ethereum/DESIGN.md §5.
		// Profile field reuse in api-agent: SolHTTP=Engine, PBFTHTTP=Beacon, Metrics=ConsensusP2P.
		{
			Network:           "ethereum",
			Env:               "mainnet",
			DisplayName:       "Ethereum Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39690,
			DefaultAgentPort:  39790,
			DefaultP2PPort:    30303,
			DefaultNodeHTTP:   8545,
			ChainFlag:         "mainnet",
			WatchSlug:         "ethereum",
			DiskHintGiB:       2048,
			OptPath:           "/opt/ethereum/mainnet",
			EtcPath:           "/etc/ethereum/mainnet",
			DataPath:          "/data/ethereum/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "ethereum-geth",
			NodeBinaryHint:    "geth",
		},
		{
			Network:           "ethereum",
			Env:               "sepolia",
			DisplayName:       "Ethereum Sepolia",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39691,
			DefaultAgentPort:  39791,
			DefaultP2PPort:    30313,
			DefaultNodeHTTP:   8546,
			ChainFlag:         "sepolia",
			WatchSlug:         "ethereum-sepolia",
			DiskHintGiB:       400,
			OptPath:           "/opt/ethereum/sepolia",
			EtcPath:           "/etc/ethereum/sepolia",
			DataPath:          "/data/ethereum/sepolia",
			ExtraSteps:        nil,
			ServicePrefix:     "ethereum-geth",
			NodeBinaryHint:    "geth",
		},
		{
			Network:           "ethereum",
			Env:               "hoodi",
			DisplayName:       "Ethereum Hoodi",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39692,
			DefaultAgentPort:  39792,
			DefaultP2PPort:    30323,
			DefaultNodeHTTP:   8547,
			ChainFlag:         "hoodi",
			WatchSlug:         "ethereum-hoodi",
			DiskHintGiB:       400,
			OptPath:           "/opt/ethereum/hoodi",
			EtcPath:           "/etc/ethereum/hoodi",
			DataPath:          "/data/ethereum/hoodi",
			ExtraSteps:        nil,
			ServicePrefix:     "ethereum-geth",
			NodeBinaryHint:    "geth",
		},
		// BSC — bnb-chain/bsc geth fork (Parlia). Ports MUST NOT collide with ethereum 3969x/3979x.
		// Canonical: deploy/nodes/bsc/DESIGN.md §5.
		{
			Network:           "bsc",
			Env:               "mainnet",
			DisplayName:       "BNB Smart Chain Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39890,
			DefaultAgentPort:  39990,
			DefaultP2PPort:    30311,
			DefaultNodeHTTP:   8575,
			ChainFlag:         "56",
			WatchSlug:         "bsc",
			DiskHintGiB:       2048,
			OptPath:           "/opt/bsc/mainnet",
			EtcPath:           "/etc/bsc/mainnet",
			DataPath:          "/data/bsc/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "bsc",
			NodeBinaryHint:    "bsc-geth",
		},
		{
			Network:           "bsc",
			Env:               "testnet",
			DisplayName:       "BNB Smart Chain Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 39891,
			DefaultAgentPort:  39991,
			DefaultP2PPort:    30312,
			DefaultNodeHTTP:   8576,
			ChainFlag:         "97",
			WatchSlug:         "bsc-testnet",
			DiskHintGiB:       400,
			OptPath:           "/opt/bsc/testnet",
			EtcPath:           "/etc/bsc/testnet",
			DataPath:          "/data/bsc/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "bsc",
			NodeBinaryHint:    "bsc-geth",
		},
		// Hyperliquid — hl-visor non-validator + --serve-eth-rpc. Canonical: deploy/nodes/hyperliquid/DESIGN.md.
		{
			Network:           "hyperliquid",
			Env:               "mainnet",
			DisplayName:       "Hyperliquid Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40090,
			DefaultAgentPort:  40190,
			DefaultP2PPort:    4001,
			DefaultNodeHTTP:   3001,
			ChainFlag:         "Mainnet",
			WatchSlug:         "hyperliquid",
			DiskHintGiB:       1024,
			OptPath:           "/opt/hyperliquid/mainnet",
			EtcPath:           "/etc/hyperliquid/mainnet",
			DataPath:          "/data/hyperliquid/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "hyperliquid",
			NodeBinaryHint:    "hl-visor",
		},
		{
			Network:           "hyperliquid",
			Env:               "testnet",
			DisplayName:       "Hyperliquid Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40093,
			DefaultAgentPort:  40193,
			DefaultP2PPort:    4011,
			// hl-visor --serve-eth-rpc always binds HyperEVM on :3001 (one_env_per_host).
			DefaultNodeHTTP: 3001,
			ChainFlag:       "Testnet",
			WatchSlug:       "hyperliquid-testnet",
			DiskHintGiB:     512,
			OptPath:         "/opt/hyperliquid/testnet",
			EtcPath:         "/etc/hyperliquid/testnet",
			DataPath:        "/data/hyperliquid/testnet",
			ExtraSteps:      nil,
			ServicePrefix:   "hyperliquid",
			NodeBinaryHint:  "hl-visor",
		},
		// Arbitrum — nitro-node full. --init.latest=pruned on first start (= full, not lite/archive).
		// Paths under /…/arbitrum; product slug=arb. Sync via eth_syncing (ibd UI).
		{
			Network:           "arb",
			Env:               "mainnet",
			DisplayName:       "Arbitrum One Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40091,
			DefaultAgentPort:  40191,
			DefaultP2PPort:    0,
			DefaultNodeHTTP:   8547,
			ChainFlag:         "42161",
			WatchSlug:         "arb",
			DiskHintGiB:       1024,
			OptPath:           "/opt/arbitrum/mainnet",
			EtcPath:           "/etc/arbitrum/mainnet",
			DataPath:          "/data/arbitrum/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "arb",
			NodeBinaryHint:    "nitro",
		},
		{
			Network:           "arb",
			Env:               "sepolia",
			DisplayName:       "Arbitrum Sepolia",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40094,
			DefaultAgentPort:  40194,
			DefaultP2PPort:    0,
			DefaultNodeHTTP:   8657,
			ChainFlag:         "421614",
			WatchSlug:         "arb-sepolia",
			DiskHintGiB:       400,
			OptPath:           "/opt/arbitrum/sepolia",
			EtcPath:           "/etc/arbitrum/sepolia",
			DataPath:          "/data/arbitrum/sepolia",
			ExtraSteps:        nil,
			ServicePrefix:     "arb",
			NodeBinaryHint:    "nitro",
		},
		// Robinhood Chain — Arbitrum Nitro (Orbit), same nitro-node binary as arb.
		// Required pruned --init.url (L1 beacon without archive blobs stalls genesis IBD).
		// Canonical: deploy/nodes/robinhood/DESIGN.md.
		{
			Network:            "robinhood",
			Env:                "mainnet",
			DisplayName:        "Robinhood Chain Mainnet",
			SnapshotPolicy:     SnapshotRequired,
			AutoSnapshot:       true,
			AutoStartNode:      true,
			DefaultSnapshotURL: "https://robinhood-snapshots.offchainlabs.com/robinhood%20chain/2026-08-03-1432f687/",
			DefaultPublicPort:  42090,
			DefaultAgentPort:   42190,
			DefaultP2PPort:     0,
			DefaultNodeHTTP:    8567,
			ChainFlag:          "4663",
			WatchSlug:          "robinhood",
			DiskHintGiB:        2048,
			OptPath:            "/opt/robinhood/mainnet",
			EtcPath:            "/etc/robinhood/mainnet",
			DataPath:           "/data/robinhood/mainnet",
			ExtraSteps:         []string{StepSnapshot},
			ServicePrefix:      "robinhood",
			NodeBinaryHint:     "nitro",
		},
		{
			Network:            "robinhood",
			Env:                "testnet",
			DisplayName:        "Robinhood Chain Testnet",
			SnapshotPolicy:     SnapshotRequired,
			AutoSnapshot:       true,
			AutoStartNode:      true,
			DefaultSnapshotURL: "https://robinhood-snapshots.offchainlabs.com/robinhood%20chain%20sepolia/2026-08-06-dacda195/",
			DefaultPublicPort:  42091,
			DefaultAgentPort:   42191,
			DefaultP2PPort:     0,
			DefaultNodeHTTP:    8569,
			ChainFlag:          "46630",
			WatchSlug:          "robinhood-testnet",
			DiskHintGiB:        400,
			OptPath:            "/opt/robinhood/testnet",
			EtcPath:            "/etc/robinhood/testnet",
			DataPath:           "/data/robinhood/testnet",
			ExtraSteps:         []string{StepSnapshot},
			ServicePrefix:      "robinhood",
			NodeBinaryHint:     "nitro",
		},
		// Optimism — op-geth + op-node. L1 RPC+Beacon from ethereum host.
		{
			Network:           "optimism",
			Env:               "mainnet",
			DisplayName:       "Optimism Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40092,
			DefaultAgentPort:  40192,
			DefaultP2PPort:    30333,
			DefaultNodeHTTP:   8549,
			ChainFlag:         "op-mainnet",
			WatchSlug:         "optimism",
			DiskHintGiB:       1024,
			OptPath:           "/opt/optimism/mainnet",
			EtcPath:           "/etc/optimism/mainnet",
			DataPath:          "/data/optimism/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "optimism",
			NodeBinaryHint:    "op-geth",
		},
		{
			Network:           "optimism",
			Env:               "sepolia",
			DisplayName:       "Optimism Sepolia",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40095,
			DefaultAgentPort:  40195,
			DefaultP2PPort:    30343,
			DefaultNodeHTTP:   8649,
			ChainFlag:         "op-sepolia",
			WatchSlug:         "optimism-sepolia",
			DiskHintGiB:       400,
			OptPath:           "/opt/optimism/sepolia",
			EtcPath:           "/etc/optimism/sepolia",
			DataPath:          "/data/optimism/sepolia",
			ExtraSteps:        nil,
			ServicePrefix:     "optimism",
			NodeBinaryHint:    "op-geth",
		},
		// Base — base-reth-node + base-consensus (Base V1). Canonical: deploy/nodes/base/DESIGN.md.
		{
			Network:           "base",
			Env:               "mainnet",
			DisplayName:       "Base Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 42290,
			DefaultAgentPort:  42390,
			DefaultP2PPort:    30353,
			DefaultNodeHTTP:   8571,
			ChainFlag:         "base",
			WatchSlug:         "base",
			DiskHintGiB:       4096,
			OptPath:           "/opt/base/mainnet",
			EtcPath:           "/etc/base/mainnet",
			DataPath:          "/data/base/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "base",
			NodeBinaryHint:    "base-reth-node",
		},
		{
			Network:           "base",
			Env:               "sepolia",
			DisplayName:       "Base Sepolia",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 42291,
			DefaultAgentPort:  42391,
			DefaultP2PPort:    30354,
			DefaultNodeHTTP:   8573,
			ChainFlag:         "base-sepolia",
			WatchSlug:         "base-sepolia",
			DiskHintGiB:       512,
			OptPath:           "/opt/base/sepolia",
			EtcPath:           "/etc/base/sepolia",
			DataPath:          "/data/base/sepolia",
			ExtraSteps:        nil,
			ServicePrefix:     "base",
			NodeBinaryHint:    "base-reth-node",
		},
		// Zcash — Zebra (zebrad). Canonical: deploy/nodes/zcash/DESIGN.md.
		{
			Network:           "zcash",
			Env:               "mainnet",
			DisplayName:       "Zcash Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 42490,
			DefaultAgentPort:  42590,
			DefaultP2PPort:    8233,
			DefaultNodeHTTP:   8232,
			WatchSlug:         "zcash",
			DiskHintGiB:       300,
			OptPath:           "/opt/zcash/mainnet",
			EtcPath:           "/etc/zcash/mainnet",
			DataPath:          "/data/zcash/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "zcash",
			NodeBinaryHint:    "zebrad",
		},
		{
			Network:           "zcash",
			Env:               "testnet",
			DisplayName:       "Zcash Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 42491,
			DefaultAgentPort:  42591,
			DefaultP2PPort:    18233,
			DefaultNodeHTTP:   18232,
			ChainFlag:         "Testnet",
			WatchSlug:         "zcash-testnet",
			DiskHintGiB:       64,
			OptPath:           "/opt/zcash/testnet",
			EtcPath:           "/etc/zcash/testnet",
			DataPath:          "/data/zcash/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "zcash",
			NodeBinaryHint:    "zebrad",
		},
		// Sui — sui-node. Canonical: deploy/nodes/sui/DESIGN.md.
		// Formal snapshot (sui-tool --no-sign-request) required — do not sync from genesis.
		{
			Network:            "sui",
			Env:                "mainnet",
			DisplayName:        "Sui Mainnet",
			SnapshotPolicy:     SnapshotRequired,
			AutoSnapshot:       true,
			AutoStartNode:      true,
			DefaultSnapshotURL: "formal-r2://mainnet",
			DefaultPublicPort:  42690,
			DefaultAgentPort:   42790,
			DefaultP2PPort:     8084,
			DefaultNodeHTTP:    9000,
			WatchSlug:          "sui",
			DiskHintGiB:        2048,
			OptPath:            "/opt/sui/mainnet",
			EtcPath:            "/etc/sui/mainnet",
			DataPath:           "/data/sui/mainnet",
			ExtraSteps:         []string{StepSnapshot},
			ServicePrefix:      "sui",
			NodeBinaryHint:     "sui-node",
		},
		{
			Network:            "sui",
			Env:                "testnet",
			DisplayName:        "Sui Testnet",
			SnapshotPolicy:     SnapshotRequired,
			AutoSnapshot:       true,
			AutoStartNode:      true,
			DefaultSnapshotURL: "formal-r2://testnet",
			DefaultPublicPort:  42691,
			DefaultAgentPort:   42791,
			DefaultP2PPort:     8085,
			DefaultNodeHTTP:    9001,
			WatchSlug:          "sui-testnet",
			DiskHintGiB:        512,
			OptPath:            "/opt/sui/testnet",
			EtcPath:            "/etc/sui/testnet",
			DataPath:           "/data/sui/testnet",
			ExtraSteps:         []string{StepSnapshot},
			ServicePrefix:      "sui",
			NodeBinaryHint:     "sui-node",
		},
		// Aptos — aptos-node. Canonical: deploy/nodes/aptos/DESIGN.md.
		{
			Network:           "aptos",
			Env:               "mainnet",
			DisplayName:       "Aptos Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 42890,
			DefaultAgentPort:  42990,
			DefaultP2PPort:    6180,
			DefaultNodeHTTP:   8080,
			WatchSlug:         "aptos",
			DiskHintGiB:       2048,
			OptPath:           "/opt/aptos/mainnet",
			EtcPath:           "/etc/aptos/mainnet",
			DataPath:          "/data/aptos/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "aptos",
			NodeBinaryHint:    "aptos-node",
		},
		{
			Network:           "aptos",
			Env:               "testnet",
			DisplayName:       "Aptos Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 42891,
			DefaultAgentPort:  42991,
			DefaultP2PPort:    6182,
			DefaultNodeHTTP:   8081,
			WatchSlug:         "aptos-testnet",
			DiskHintGiB:       512,
			OptPath:           "/opt/aptos/testnet",
			EtcPath:           "/etc/aptos/testnet",
			DataPath:          "/data/aptos/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "aptos",
			NodeBinaryHint:    "aptos-node",
		},
		// Avalanche — avalanchego C-Chain archive. Canonical: deploy/nodes/avalanche/DESIGN.md.
		{
			Network:           "avalanche",
			Env:               "mainnet",
			DisplayName:       "Avalanche Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 43090,
			DefaultAgentPort:  43190,
			DefaultP2PPort:    9651,
			DefaultNodeHTTP:   9650,
			WatchSlug:         "avalanche",
			DiskHintGiB:       4096,
			OptPath:           "/opt/avalanche/mainnet",
			EtcPath:           "/etc/avalanche/mainnet",
			DataPath:          "/data/avalanche/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "avalanche",
			NodeBinaryHint:    "avalanchego",
		},
		{
			Network:           "avalanche",
			Env:               "fuji",
			DisplayName:       "Avalanche Fuji",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 43091,
			DefaultAgentPort:  43191,
			DefaultP2PPort:    9661,
			DefaultNodeHTTP:   9660,
			WatchSlug:         "avalanche-fuji",
			DiskHintGiB:       512,
			OptPath:           "/opt/avalanche/fuji",
			EtcPath:           "/etc/avalanche/fuji",
			DataPath:          "/data/avalanche/fuji",
			ExtraSteps:        nil,
			ServicePrefix:     "avalanche",
			NodeBinaryHint:    "avalanchego",
		},
		// XRPL — stock xrpld. Canonical: deploy/nodes/xrpl/DESIGN.md.
		{
			Network:           "xrpl",
			Env:               "mainnet",
			DisplayName:       "XRP Ledger Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40290,
			DefaultAgentPort:  40390,
			DefaultP2PPort:    51235,
			DefaultNodeHTTP:   5005,
			ChainFlag:         "mainnet",
			WatchSlug:         "xrpl",
			DiskHintGiB:       1024,
			OptPath:           "/opt/xrpl/mainnet",
			EtcPath:           "/etc/xrpl/mainnet",
			DataPath:          "/data/xrpl/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "xrpl",
			NodeBinaryHint:    "xrpld",
		},
		{
			Network:           "xrpl",
			Env:               "testnet",
			DisplayName:       "XRP Ledger Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40291,
			DefaultAgentPort:  40391,
			DefaultP2PPort:    51236,
			DefaultNodeHTTP:   5006,
			ChainFlag:         "testnet",
			WatchSlug:         "xrpl-testnet",
			DiskHintGiB:       128,
			OptPath:           "/opt/xrpl/testnet",
			EtcPath:           "/etc/xrpl/testnet",
			DataPath:          "/data/xrpl/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "xrpl",
			NodeBinaryHint:    "xrpld",
		},
		// Dogecoin — stock dogecoind IBD (no snapshot). Canonical: deploy/nodes/doge/DESIGN.md.
		{
			Network:           "doge",
			Env:               "mainnet",
			DisplayName:       "Dogecoin Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40490,
			DefaultAgentPort:  40590,
			DefaultP2PPort:    22556,
			DefaultNodeHTTP:   22555,
			WatchSlug:         "doge",
			DiskHintGiB:       400,
			OptPath:           "/opt/doge/mainnet",
			EtcPath:           "/etc/doge/mainnet",
			DataPath:          "/data/doge/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "doge",
			NodeBinaryHint:    "dogecoind",
		},
		{
			Network:           "doge",
			Env:               "testnet",
			DisplayName:       "Dogecoin Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40491,
			DefaultAgentPort:  40591,
			DefaultP2PPort:    44556,
			DefaultNodeHTTP:   44555,
			ChainFlag:         "testnet=1",
			WatchSlug:         "doge-testnet",
			DiskHintGiB:       64,
			OptPath:           "/opt/doge/testnet",
			EtcPath:           "/etc/doge/testnet",
			DataPath:          "/data/doge/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "doge",
			NodeBinaryHint:    "dogecoind",
		},
		// Litecoin — stock litecoind IBD. Canonical: deploy/nodes/ltc/DESIGN.md.
		{
			Network:           "ltc",
			Env:               "mainnet",
			DisplayName:       "Litecoin Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41090,
			DefaultAgentPort:  41190,
			DefaultP2PPort:    9333,
			DefaultNodeHTTP:   9332,
			WatchSlug:         "ltc",
			DiskHintGiB:       200,
			OptPath:           "/opt/ltc/mainnet",
			EtcPath:           "/etc/ltc/mainnet",
			DataPath:          "/data/ltc/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "ltc",
			NodeBinaryHint:    "litecoind",
		},
		{
			Network:           "ltc",
			Env:               "testnet",
			DisplayName:       "Litecoin Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41091,
			DefaultAgentPort:  41191,
			DefaultP2PPort:    19333,
			DefaultNodeHTTP:   19332,
			ChainFlag:         "testnet=1",
			WatchSlug:         "ltc-testnet",
			DiskHintGiB:       40,
			OptPath:           "/opt/ltc/testnet",
			EtcPath:           "/etc/ltc/testnet",
			// litecoind testnet=1 nests as datadir/testnet4 (not testnet3).
			DataPath:       "/data/ltc/testnet4",
			ExtraSteps:     nil,
			ServicePrefix:  "ltc",
			NodeBinaryHint: "litecoind",
		},
		{
			Network:           "ltc",
			Env:               "regtest",
			DisplayName:       "Litecoin Regtest",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41092,
			DefaultAgentPort:  41192,
			DefaultP2PPort:    19444,
			DefaultNodeHTTP:   19443,
			ChainFlag:         "regtest=1",
			WatchSlug:         "ltc-regtest",
			DiskHintGiB:       8,
			OptPath:           "/opt/ltc/regtest",
			EtcPath:           "/etc/ltc/regtest",
			DataPath:          "/data/ltc/regtest",
			ExtraSteps:        nil,
			ServicePrefix:     "ltc",
			NodeBinaryHint:    "litecoind",
		},
		// Dash — stock dashd IBD. Canonical: deploy/nodes/dash/DESIGN.md.
		{
			Network:           "dash",
			Env:               "mainnet",
			DisplayName:       "Dash Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41290,
			DefaultAgentPort:  41390,
			DefaultP2PPort:    9999,
			DefaultNodeHTTP:   9998,
			WatchSlug:         "dash",
			DiskHintGiB:       100,
			OptPath:           "/opt/dash/mainnet",
			EtcPath:           "/etc/dash/mainnet",
			DataPath:          "/data/dash/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "dash",
			NodeBinaryHint:    "dashd",
		},
		{
			Network:           "dash",
			Env:               "testnet",
			DisplayName:       "Dash Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41291,
			DefaultAgentPort:  41391,
			DefaultP2PPort:    19999,
			DefaultNodeHTTP:   19998,
			ChainFlag:         "testnet=1",
			WatchSlug:         "dash-testnet",
			DiskHintGiB:       32,
			OptPath:           "/opt/dash/testnet",
			EtcPath:           "/etc/dash/testnet",
			DataPath:          "/data/dash/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "dash",
			NodeBinaryHint:    "dashd",
		},
		{
			Network:           "dash",
			Env:               "regtest",
			DisplayName:       "Dash Regtest",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41292,
			DefaultAgentPort:  41392,
			DefaultP2PPort:    19899,
			DefaultNodeHTTP:   19898,
			ChainFlag:         "regtest=1",
			WatchSlug:         "dash-regtest",
			DiskHintGiB:       8,
			OptPath:           "/opt/dash/regtest",
			EtcPath:           "/etc/dash/regtest",
			DataPath:          "/data/dash/regtest",
			ExtraSteps:        nil,
			ServicePrefix:     "dash",
			NodeBinaryHint:    "dashd",
		},
		// Bitcoin Cash (BCHN) — IBD; RPC remapped off bitcoin :8332. Canonical: deploy/nodes/bch/DESIGN.md.
		{
			Network:           "bch",
			Env:               "mainnet",
			DisplayName:       "Bitcoin Cash Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41490,
			DefaultAgentPort:  41590,
			DefaultP2PPort:    8433,
			DefaultNodeHTTP:   8432,
			WatchSlug:         "bch",
			DiskHintGiB:       400,
			OptPath:           "/opt/bch/mainnet",
			EtcPath:           "/etc/bch/mainnet",
			DataPath:          "/data/bch/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "bch",
			NodeBinaryHint:    "bitcoind",
		},
		{
			Network:           "bch",
			Env:               "testnet",
			DisplayName:       "Bitcoin Cash Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41491,
			DefaultAgentPort:  41591,
			DefaultP2PPort:    18433,
			DefaultNodeHTTP:   18432,
			ChainFlag:         "testnet=1",
			WatchSlug:         "bch-testnet",
			DiskHintGiB:       64,
			OptPath:           "/opt/bch/testnet",
			EtcPath:           "/etc/bch/testnet",
			DataPath:          "/data/bch/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "bch",
			NodeBinaryHint:    "bitcoind",
		},
		{
			Network:           "bch",
			Env:               "regtest",
			DisplayName:       "Bitcoin Cash Regtest",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41492,
			DefaultAgentPort:  41592,
			DefaultP2PPort:    18544,
			DefaultNodeHTTP:   18543,
			ChainFlag:         "regtest=1",
			WatchSlug:         "bch-regtest",
			DiskHintGiB:       8,
			OptPath:           "/opt/bch/regtest",
			EtcPath:           "/etc/bch/regtest",
			DataPath:          "/data/bch/regtest",
			ExtraSteps:        nil,
			ServicePrefix:     "bch",
			NodeBinaryHint:    "bitcoind",
		},
		// Cardano — cardano-node + Ogmios. Canonical: deploy/nodes/cardano/DESIGN.md.
		{
			Network:            "cardano",
			Env:                "mainnet",
			DisplayName:        "Cardano Mainnet",
			SnapshotPolicy:     SnapshotRequired,
			AutoSnapshot:       true,
			AutoStartNode:      true,
			DefaultSnapshotURL: "https://aggregator.release-mainnet.api.mithril.network/aggregator",
			DefaultPublicPort:  40690,
			DefaultAgentPort:   40790,
			DefaultP2PPort:     3003,
			DefaultNodeHTTP:    1337,
			ChainFlag:          "mainnet",
			WatchSlug:          "cardano",
			DiskHintGiB:        400,
			OptPath:            "/opt/cardano/mainnet",
			EtcPath:            "/etc/cardano/mainnet",
			DataPath:           "/data/cardano/mainnet",
			ExtraSteps:         []string{StepSnapshot},
			ServicePrefix:      "cardano",
			NodeBinaryHint:     "cardano-node",
		},
		{
			Network:            "cardano",
			Env:                "preprod",
			DisplayName:        "Cardano Preprod",
			SnapshotPolicy:     SnapshotRequired,
			AutoSnapshot:       true,
			AutoStartNode:      true,
			DefaultSnapshotURL: "https://aggregator.release-preprod.api.mithril.network/aggregator",
			DefaultPublicPort:  40691,
			DefaultAgentPort:   40791,
			DefaultP2PPort:     3004,
			DefaultNodeHTTP:    1338,
			ChainFlag:          "preprod",
			WatchSlug:          "cardano-preprod",
			DiskHintGiB:        80,
			OptPath:            "/opt/cardano/preprod",
			EtcPath:            "/etc/cardano/preprod",
			DataPath:           "/data/cardano/preprod",
			ExtraSteps:         []string{StepSnapshot},
			ServicePrefix:      "cardano",
			NodeBinaryHint:     "cardano-node",
		},
		{
			Network:            "cardano",
			Env:                "preview",
			DisplayName:        "Cardano Preview",
			SnapshotPolicy:     SnapshotRequired,
			AutoSnapshot:       true,
			AutoStartNode:      true,
			DefaultSnapshotURL: "https://aggregator.pre-release-preview.api.mithril.network/aggregator",
			DefaultPublicPort:  40692,
			DefaultAgentPort:   40792,
			DefaultP2PPort:     3005,
			DefaultNodeHTTP:    1339,
			ChainFlag:          "preview",
			WatchSlug:          "cardano-preview",
			DiskHintGiB:        80,
			OptPath:            "/opt/cardano/preview",
			EtcPath:            "/etc/cardano/preview",
			DataPath:           "/data/cardano/preview",
			ExtraSteps:         []string{StepSnapshot},
			ServicePrefix:      "cardano",
			NodeBinaryHint:     "cardano-node",
		},
		// Stellar — stellar-rpc + Captive Core. Canonical: deploy/nodes/stellar/DESIGN.md.
		{
			Network:           "stellar",
			Env:               "mainnet",
			DisplayName:       "Stellar Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40890,
			DefaultAgentPort:  40990,
			DefaultP2PPort:    11625,
			DefaultNodeHTTP:   8000,
			ChainFlag:         "mainnet",
			WatchSlug:         "stellar",
			DiskHintGiB:       512,
			OptPath:           "/opt/stellar/mainnet",
			EtcPath:           "/etc/stellar/mainnet",
			DataPath:          "/data/stellar/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "stellar",
			NodeBinaryHint:    "stellar-rpc",
		},
		{
			Network:           "stellar",
			Env:               "testnet",
			DisplayName:       "Stellar Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40891,
			DefaultAgentPort:  40991,
			DefaultP2PPort:    11627,
			DefaultNodeHTTP:   8001,
			ChainFlag:         "testnet",
			WatchSlug:         "stellar-testnet",
			DiskHintGiB:       128,
			OptPath:           "/opt/stellar/testnet",
			EtcPath:           "/etc/stellar/testnet",
			DataPath:          "/data/stellar/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "stellar",
			NodeBinaryHint:    "stellar-rpc",
		},
		{
			Network:           "stellar",
			Env:               "futurenet",
			DisplayName:       "Stellar Futurenet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 40892,
			DefaultAgentPort:  40992,
			DefaultP2PPort:    11629,
			DefaultNodeHTTP:   8002,
			ChainFlag:         "futurenet",
			WatchSlug:         "stellar-futurenet",
			DiskHintGiB:       128,
			OptPath:           "/opt/stellar/futurenet",
			EtcPath:           "/etc/stellar/futurenet",
			DataPath:          "/data/stellar/futurenet",
			ExtraSteps:        nil,
			ServicePrefix:     "stellar",
			NodeBinaryHint:    "stellar-rpc",
		},
		// Toncoin — MyTonCtrl liteserver + THA. Canonical: deploy/nodes/ton/DESIGN.md.
		{
			Network:           "ton",
			Env:               "mainnet",
			DisplayName:       "Toncoin Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41690,
			DefaultAgentPort:  41790,
			DefaultP2PPort:    30310,
			DefaultNodeHTTP:   8081,
			ChainFlag:         "mainnet",
			WatchSlug:         "ton",
			DiskHintGiB:       1024,
			OptPath:           "/opt/ton/mainnet",
			EtcPath:           "/etc/ton/mainnet",
			DataPath:          "/data/ton/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "ton",
			NodeBinaryHint:    "validator-engine",
		},
		{
			Network:           "ton",
			Env:               "testnet",
			DisplayName:       "Toncoin Testnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41691,
			DefaultAgentPort:  41791,
			DefaultP2PPort:    30311,
			DefaultNodeHTTP:   8082,
			ChainFlag:         "testnet",
			WatchSlug:         "ton-testnet",
			DiskHintGiB:       256,
			OptPath:           "/opt/ton/testnet",
			EtcPath:           "/etc/ton/testnet",
			DataPath:          "/data/ton/testnet",
			ExtraSteps:        nil,
			ServicePrefix:     "ton",
			NodeBinaryHint:    "validator-engine",
		},
		// Ethereum Classic — Core-Geth archive. Canonical: deploy/nodes/etc/DESIGN.md.
		{
			Network:           "etc",
			Env:               "mainnet",
			DisplayName:       "Ethereum Classic Mainnet",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41890,
			DefaultAgentPort:  41990,
			DefaultP2PPort:    30323,
			DefaultNodeHTTP:   8555,
			ChainFlag:         "--classic",
			WatchSlug:         "etc",
			DiskHintGiB:       1024,
			OptPath:           "/opt/etc/mainnet",
			EtcPath:           "/etc/etc/mainnet",
			DataPath:          "/data/etc/mainnet",
			ExtraSteps:        nil,
			ServicePrefix:     "etc",
			NodeBinaryHint:    "geth",
		},
		{
			Network:           "etc",
			Env:               "mordor",
			DisplayName:       "Ethereum Classic Mordor",
			SnapshotPolicy:    SnapshotNever,
			AutoSnapshot:      false,
			AutoStartNode:     true,
			DefaultPublicPort: 41891,
			DefaultAgentPort:  41991,
			DefaultP2PPort:    30324,
			DefaultNodeHTTP:   8556,
			ChainFlag:         "--mordor",
			WatchSlug:         "etc-mordor",
			DiskHintGiB:       128,
			OptPath:           "/opt/etc/mordor",
			EtcPath:           "/etc/etc/mordor",
			DataPath:          "/data/etc/mordor",
			ExtraSteps:        nil,
			ServicePrefix:     "etc",
			NodeBinaryHint:    "geth",
		},
	}
}

// LookupNetworkProfile returns a registered profile, or a safe generic fallback
// (common steps only, no snapshot) so unknown networks never inherit TRON policy.
func LookupNetworkProfile(network, env string) NetworkProfile {
	net := strings.ToLower(strings.TrimSpace(network))
	if net == "" {
		net = DefaultNetwork
	}
	e := strings.ToLower(strings.TrimSpace(env))
	if e == "" && net == DefaultNetwork {
		e = DefaultEnv
	}
	if net == "avalanche" && e == "testnet" {
		e = "fuji"
	}

	if p, ok := networkProfiles[profileKey(net, e)]; ok {
		return p
	}

	// Optional wildcard: RegisterNetworkProfile({Network: "solana", Env: "*", ...}).
	if p, ok := networkProfiles[profileKey(net, "*")]; ok {
		p.Env = e
		p.ID = profileKey(net, e)
		if p.DisplayName == "" || strings.HasSuffix(p.DisplayName, "/*") {
			p.DisplayName = net + "/" + e
		}
		return p
	}

	return NetworkProfile{
		ID:             profileKey(net, e),
		Network:        net,
		Env:            e,
		DisplayName:    net + "/" + e,
		SnapshotPolicy: SnapshotNever,
		AutoStartNode:  true,
		ServicePrefix:  net,
	}
}

// ListKnownEnvs returns registered env ids for a network (sorted, stable for UI).
func ListKnownEnvs(network string) []string {
	net := strings.ToLower(strings.TrimSpace(network))
	if net == "" {
		net = DefaultNetwork
	}
	seen := map[string]struct{}{}
	out := make([]string, 0)
	for _, p := range networkProfiles {
		if p.Network != net || p.Env == "" || p.Env == "*" {
			continue
		}
		if _, ok := seen[p.Env]; ok {
			continue
		}
		seen[p.Env] = struct{}{}
		out = append(out, p.Env)
	}
	sort.Strings(out)
	return out
}

// AllNetworkProfiles returns unique catalog entries sorted by ID.
func AllNetworkProfiles() []NetworkProfile {
	out := make([]NetworkProfile, 0, len(networkProfiles))
	for _, p := range networkProfiles {
		out = append(out, p)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out
}

// HasExtra reports whether ExtraSteps contains id (e.g. StepSnapshot).
func (p NetworkProfile) HasExtra(id string) bool {
	for _, s := range p.ExtraSteps {
		if s == id {
			return true
		}
	}
	return false
}

// SupportedLifecycleSteps is the static step plan for this profile (UI filter source of truth):
// ports → install → [ExtraSteps…] → start → run.
// Unlike runtime lifecycle.steps / profile.step_ids, this does not omit snapshot when disabled
// mid-flight — it declares what the network can run.
func (p NetworkProfile) SupportedLifecycleSteps() []string {
	steps := []string{"ports", "install"}
	if len(p.ExtraSteps) > 0 {
		steps = append(steps, p.ExtraSteps...)
	}
	steps = append(steps, "start", "run")
	return steps
}

// LifecycleCapabilities exposes boolean feature flags derived from the profile.
func (p NetworkProfile) LifecycleCapabilities() map[string]bool {
	snap := p.HasExtra(StepSnapshot) || p.SnapshotPolicy != SnapshotNever
	// Snapshot-then-catch-up chains (robinhood) keep ibd=true so Sync UI stays after snapshot.
	ibdCore := strings.EqualFold(p.Network, "bitcoin") ||
		strings.EqualFold(p.Network, "doge") ||
		strings.EqualFold(p.Network, "ltc") ||
		strings.EqualFold(p.Network, "dash") ||
		strings.EqualFold(p.Network, "bch") ||
		strings.EqualFold(p.Network, "cardano") ||
		strings.EqualFold(p.Network, "ethereum") ||
		strings.EqualFold(p.Network, "bsc") ||
		strings.EqualFold(p.Network, "hyperliquid") ||
		strings.EqualFold(p.Network, "arb") ||
		strings.EqualFold(p.Network, "robinhood") ||
		strings.EqualFold(p.Network, "optimism") ||
		strings.EqualFold(p.Network, "base") ||
		strings.EqualFold(p.Network, "xrpl") ||
		strings.EqualFold(p.Network, "stellar") ||
		strings.EqualFold(p.Network, "ton") ||
		strings.EqualFold(p.Network, "etc") ||
		strings.EqualFold(p.Network, "zcash") ||
		strings.EqualFold(p.Network, "sui") ||
		strings.EqualFold(p.Network, "aptos") ||
		strings.EqualFold(p.Network, "avalanche")
	ibd := ibdCore && (!snap || strings.EqualFold(p.Network, "robinhood") ||
		strings.EqualFold(p.Network, "sui") || strings.EqualFold(p.Network, "cardano"))
	// Regtest is local — do not advertise IBD sync UI.
	if ibd && isBitcoinRegtest(p.Env) {
		ibd = false
	}
	return map[string]bool{
		"snapshot": snap,
		"ibd":      ibd,
	}
}

// SupportedLifecycleSteps looks up the catalog and returns the static step ids.
func SupportedLifecycleSteps(network, env string) []string {
	return LookupNetworkProfile(network, env).SupportedLifecycleSteps()
}

// LifecycleCapabilitiesFor looks up the catalog capabilities map.
func LifecycleCapabilitiesFor(network, env string) map[string]bool {
	return LookupNetworkProfile(network, env).LifecycleCapabilities()
}

// ListKnownNetworks returns distinct network ids from the catalog (sorted).
func ListKnownNetworks() []string {
	seen := map[string]struct{}{}
	out := make([]string, 0)
	for _, p := range networkProfiles {
		if p.Network == "" {
			continue
		}
		if _, ok := seen[p.Network]; ok {
			continue
		}
		seen[p.Network] = struct{}{}
		out = append(out, p.Network)
	}
	sort.Strings(out)
	return out
}

// ServiceUnit is the systemd unit for the chain node process.
func (p NetworkProfile) ServiceUnit() string {
	env := p.Env
	if env == "" {
		env = DefaultEnv
	}
	prefix := p.ServicePrefix
	if prefix == "" {
		prefix = p.Network
	}
	return fmt.Sprintf("%s-%s.service", prefix, env)
}

// CookieRelPath is the bitcoind datadir-relative .cookie path for this env.
func (p NetworkProfile) CookieRelPath() string {
	switch p.Env {
	case "testnet4":
		return "testnet4/.cookie"
	case "signet":
		return "signet/.cookie"
	case "regtest":
		return "regtest/.cookie"
	default:
		return ".cookie"
	}
}
