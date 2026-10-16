# Network intake — Aleo (snarkOS client)

VERIFIED = read from upstream source / curl at snarkOS v4.12.0 (2026-10-13/14); INFERRED = not checked. Not run on a Linux host.

| Field | Answer |
|---|---|
| Network id / envs | `aleo` — **`mainnet` only** |
| Status | mainnet **implemented** (`chains/aleo`); testnet **DEFERRED** |
| Ports | 41030 REST / 41032 p2p / 41034 Prometheus (127.0.0.1) |

- **Testnet DEFERRED:** `testnet-v*` tags are prereleases with no assets and a different snarkVM (a mainnet binary cannot follow the testnet); it needs a native source-build mechanism (no Docker) — planned, not built.
- **Binary:** `aleo-<tag>-x86_64-unknown-linux-gnu.zip` (one file, `snarkos`; `unzip` host package). No linux-aarch64 asset exists. `AleoClientReleaseResolver` = highest final `v<number>` release that carries that zip.
- **Run** (flags VERIFIED in `cli/src/commands/start.rs`): `snarkos start --network=0 --client --nodisplay --node=0.0.0.0:<p2p> --rest=0.0.0.0:<rest> --rest-rps=1000 --metrics --metrics-ip=127.0.0.1:<metrics> --ledger-storage --node-data-storage --logfile=<node_dir>/logs/snarkos.log --verbosity=1`. The unit's stdout goes to `logs/snarkos.stdout` so the two never write one file. The client draws an ephemeral account (no private key on the host).
- **Bootstrap:** snarkOS prefetches blocks from `cdn.provable.com` itself (default `--cdn`), so no snapshot resolver.
- **Prod:** `rest-rps` vendor default is 10 per source IP — unusable behind a proxy where every client shares one IP — so Start ships 1000 (the one place the shipped value departs from the vendor default). JWT stays **on** (never `--nojwt`): it guards `/db_backup`, `/node/address` and the mapping routes; node-data is 0700. REST has CORS `*` and POST routes by design.
- **Height / tip:** `GET /mainnet/block/height/latest` → plain integer, also `api.explorer.provable.com/v1/mainnet/block/height/latest`; `tipLagBlocks: 5`. Hardware per the snarkOS README: 24 c / 128 GiB / 2 TB (docs.aleo.org quotes less; unverified).
