package rpcnode.toolkit.nodes.application.start

import rpcnode.toolkit.catalog.domain.NetworkId

/** Inputs the chain needs to build a host launch + height recipe. */
data class ChainNodeStartContext(
    val network: NetworkId,
    val env: String,
    val program: String,
    val configFile: String?,
    /** Host data root (blockchain role dir) — needed when CLI flags must be absolute (Bitcoin Core). */
    val nodeDir: String? = null,
    /** From `clients/<network>.yml` → `programs[].requirements.javaMajor`. */
    val javaMajor: Int? = null,
    /** From `clients/<network>.yml` → `programs[].requirements.logFile` (relative to node_dir). */
    val logFile: String? = null,
    /** Persisted wizard options JSON (e.g. ethereum `node=full|archive`). */
    val installOptionsJson: String = "",
    /** Persisted multi-disk layout JSON (Solana ledger/accounts/snapshots dirs). */
    val diskLayoutJson: String = "",
)

/**
 * How the host agent should launch the process (panel → agent).
 * Chain packages build this; the agent executes it via [HostNodeProcessStarter].
 */
data class NodeLaunchSpec(
    /** `java_jar` | `binary` */
    val kind: String,
    /** Relative to node_dir (e.g. FullNode.jar, bitcoin/bin/bitcoind). */
    val entry: String,
    val args: List<String> = emptyList(),
    /**
     * If [entry] is missing, extract the first matching archive under node_dir
     * (e.g. `*.tar.gz` for Bitcoin Core).
     */
    val extractArchiveGlob: String? = null,
    /**
     * After extract, rename the single top-level directory to this name
     * (Bitcoin Core ships `bitcoin-28.0/` → `bitcoin/`).
     */
    val normalizeDir: String? = null,
    /**
     * Required JDK major for `java_jar` (java-tron needs 8 on amd64).
     * Null = any Java on PATH / JAVA_HOME.
     */
    val javaMajor: Int? = null,
    /**
     * Process log relative to node_dir (e.g. `logs/tron.log`).
     * Null → systemd capture file `logs/node.out`.
     */
    val logFile: String? = null,
)

/** How the host agent should read local chain height. */
data class NodeHeightSpec(
    /** `tron_http` | `bitcoin_cli` */
    val kind: String,
    /** Catalog port role used by the panel to resolve [NodeHeightSpec] listen port. */
    val portRole: String,
)

data class ChainNodeStartPlan(
    val launch: NodeLaunchSpec,
    val height: NodeHeightSpec,
)

/**
 * Chain-owned Start recipe under `chains/<id>/infrastructure/start`.
 * Panel resolves the plan and sends it to the host agent.
 */
interface ChainNodeStart
{
    val networkId: NetworkId

    fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
}

/** Result of spawning a chain process on the host. */
sealed interface HostNodeStartResult
{
    data class Started(val pid: Long) : HostNodeStartResult
    data object InvalidLaunch : HostNodeStartResult
    data class Failed(val detail: String) : HostNodeStartResult
    /** Long-running host prep (e.g. Agave cargo-build) — retry Start when ready. */
    data class Pending(val detail: String) : HostNodeStartResult
}

/**
 * Chain-owned process launcher under `chains/<id>/infrastructure/proc`.
 * Lives in main; the agent wires and runs it on the host.
 */
fun interface HostNodeProcessStarter
{
    fun start(
        nodeId: String,
        network: String,
        env: String,
        nodeDir: String,
        launch: NodeLaunchSpec,
    ): HostNodeStartResult
}

/**
 * Chain-owned local height reader under `chains/<id>/infrastructure/http`.
 * Lives in main; the agent wires and runs it on the host.
 */
data class HostNodeHeightReading(
    val height: Long,
    /**
     * Host-reported sync progress 0..100 while catching tip / IBD.
     * Null = unknown (panel falls back to height vs public tip).
     */
    val syncPct: Double? = null,
    /** True while the client still reports IBD / eth_syncing / similar. */
    val syncing: Boolean = false,
)

interface HostNodeHeightProbe
{
    suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long?

    suspend fun reading(nodeDir: String, httpPort: Int, configFile: String, env: String): HostNodeHeightReading?
    {
        val h = height(nodeDir, httpPort, configFile, env) ?: return null
        return HostNodeHeightReading(height = h)
    }
}
