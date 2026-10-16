package rpcnode.toolkit.chains.opstack.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId

/** Which execution client the network runs. Both take the same reth flags; only the binary and how it is installed differ. */
enum class OpstackElFlavor(val binary: String)
{
    /** Upstream `op-reth` of the ethereum-optimism monorepo: no release assets, built (panel build fleet or host) from a pinned tag. */
    OP_RETH("op-reth"),

    /** The project's own reth fork (World Chain): GitHub release tarball per CPU architecture, nothing to compile. */
    WORLD_CHAIN("world-chain"),
}

/** `op-node --syncmode` of an env. */
enum class OpstackSyncMode(val flag: String)
{
    /** op-node drives the EL with gossiped/derived payloads. Robust without EL peers: the default where nobody publishes any. */
    CONSENSUS_LAYER("consensus-layer"),

    /** op-node tells the EL to sync towards the tip over its own P2P. Needs EL peers (op-reth ships default bootnodes). */
    EXECUTION_LAYER("execution-layer"),
}

/** Where the node's own run script restores a datadir snapshot from on the first start. */
sealed interface OpstackSnapshotSource
{
    /** No public datadir snapshot: only an operator `snapshot_url`, or a (slow) sync from L1. */
    data object None : OpstackSnapshotSource

    /**
     * Alchemy public snapshots (THIRD PARTY, free, no SLA, no checksum): [page] is `https://www.alchemy.com/snapshots/<page>`;
     * it embeds one public R2 link `…/rpc-platform/<[path]>/reth/reth-<ts>.tar.gz` (a `<path>/gt/…` geth file sits next to it).
     */
    data class Alchemy(val page: String, val path: String) : OpstackSnapshotSource

    /** Gelato ChainSnap: `<host>/index.html` lists the `reth/full` and `reth/archival` datadir `.tar.lz4` files + `.sha256` (Lisk's official source). */
    data class Gelato(val host: String) : OpstackSnapshotSource

    /** One stable URL per node type, rewritten daily by the project (World Chain's official S3 buckets). No checksum published. */
    data class Fixed(val full: String, val archive: String) : OpstackSnapshotSource
}

/** Catalog ports of one env — must match `chains/<id>/clients.yml` (the block of the network in research/PORTS.md). */
data class OpstackPorts(
    val http: Int,
    val ws: Int,
    val p2p: Int,
    val engine: Int,
    val metrics: Int,
    val clRpc: Int,
    val clP2p: Int,
    val clMetrics: Int,
    /** op-reth discv5 (UDP). Its default 9200 is shared by every op-reth on the host, so each node gets its own. */
    val discv5: Int,
)
{
    companion object
    {
        /** The block layout every network of the family uses (offsets from the env's first port). */
        fun fromBase(base: Int) = OpstackPorts(
            http = base, ws = base + 1, p2p = base + 2, engine = base + 3, metrics = base + 4,
            clRpc = base + 5, clP2p = base + 6, clMetrics = base + 7, discv5 = base + 8,
        )
    }
}

/** One env (`mainnet` | `sepolia`) of an OP Stack chain: everything the run script needs that differs from its siblings. */
data class OpstackEnvSpec(
    val id: String,
    /** op-reth / world-chain `--chain` (Superchain Registry name compiled into the binary). */
    val rethChain: String,
    /** op-node `--network` (`<registry file>-<superchain>`). */
    val nodeNetwork: String,
    /** Registry `sequencer_rpc`: where `--rollup.sequencer` forwards transactions. */
    val sequencer: String,
    /** Registry `block_time`: sizes the prune window (30 days). */
    val blockTimeSec: Int,
    val syncMode: OpstackSyncMode,
    val snapshot: OpstackSnapshotSource,
    /** op-node `--p2p.static` multiaddrs. */
    val clStatic: List<String> = emptyList(),
    /** op-node `--p2p.bootnodes` (ENR or enode URLs); empty = op-node's built-in list. */
    val clBootnodes: List<String> = emptyList(),
    /** op-reth `--trusted-peers` enodes; empty = op-reth's built-in OP Stack bootnodes only. */
    val elTrusted: List<String> = emptyList(),
    /** Extra EL flags (no values with spaces). */
    val elExtraFlags: List<String> = emptyList(),
    val ports: OpstackPorts,
)
{
    /** Blocks of state / receipts a full node keeps: 30 days. Block bodies are never pruned (op-node reads them). */
    val pruneDistance: Int get() = PRUNE_WINDOW_SEC / blockTimeSec

    companion object
    {
        const val PRUNE_WINDOW_SEC = 30 * 24 * 60 * 60
    }
}

/** One OP Stack network of the family. The shared classes read this; they never branch on the network id. */
data class OpstackNetworkSpec(
    val network: NetworkId,
    val label: String,
    val elFlavor: OpstackElFlavor = OpstackElFlavor.OP_RETH,
    val envs: List<OpstackEnvSpec>,
)
{
    /** Env of a start request: unknown ids fall back to mainnet (as optimism does). */
    fun env(raw: String): OpstackEnvSpec =
        envs.firstOrNull { it.id == raw.trim().lowercase() } ?: envs.first()

    fun normalizeEnv(raw: String): String = env(raw).id

    /** `logs/<id>.log` — also `requirements.logFile` in clients.yml. */
    val relativeLog: String get() = "logs/${network.value}.log"

    val runScript: String get() = "bin/rpcnode-${network.value}-run.sh"
}
