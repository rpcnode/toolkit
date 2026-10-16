# Non-EVM family — DEFERRED networks

Research (`docs/networks/research/nonevm/`) is kept; nothing is shipped for these.

## peaq — DEFERRED (needs a native source-build mechanism, planned)
peaq-node has no upstream binary: GitHub releases `peaq-v0.0.110`–`112` have 0 assets and the only artifact is a Docker image. The existing `build:` fleet mechanism compiles through a Dockerfile on a build host; the product rule is that Docker is not used anywhere, so peaq (and Aleo testnet) wait for a native build path. Design notes for later: pinned tag `peaq-v0.0.113`, chain spec `peaq-raw.json` from the tag (2.5 MB), `peaq-node --parachain-id 3338 --chain <spec> … -- <relay flags>` with an embedded Polkadot relay (or `--relay-chain-rpc-urls` to a panel Polkadot node), ports block 42140. `chains/substrate` (`SubstrateSpec`, `SubstrateFlags.rpcFlags`, probes, starter with `preflight`) was written so peaq can reuse it.

## hedera — DEFERRED
Permissioned consensus nodes (no operator route for a non-council party); the mirror node needs requester-pays buckets and PostgreSQL/Citus; the JSON-RPC relay needs a funded account and is a proxy, not a node. Ports block 41630-41659 stays unused.
