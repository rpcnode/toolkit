package rpcnode.toolkit.chains.evmfamily.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.EvmNodeHeightProbe
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientArtifactUrlResolver
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe

/**
 * One env of an EVM-family network: the client's own chain selector, the chain id and the first port of its block.
 * [facts] carries what only one recipe needs (checkpoint URL, genesis URL, bootnodes, …) so the shared classes never
 * branch on the network.
 */
data class EvmEnv(
    val id: String,
    /** Value the client takes for its network selector (`gnosis`, `--mainnet`, `fluent-testnet`, `costwo`, …). */
    val chain: String,
    val chainId: Long,
    val basePort: Int,
    val facts: Map<String, String> = emptyMap(),
)
{
    fun fact(key: String): String = facts[key] ?: error("env $id has no fact '$key'")
}

/**
 * Everything that differs per EVM-family network. The shared start / probe / snapshot classes read it; the per-network
 * [recipe] renders the units. Port roles are offsets from [EvmEnv.basePort] (PORTS.md: +0 http, +1 ws, +2 p2p, …).
 */
data class EvmProfile(
    val network: NetworkId,
    val recipe: EvmRecipe,
    val envs: List<EvmEnv>,
    /** Catalog port role → offset inside the env's block; must equal `clients.yml` (a test compares). */
    val portOffsets: Map<String, Int>,
    /** Disk role ids of `network.yml`, in order; the first holds the binaries (node_dir). */
    val diskRoles: List<String>,
    /** Local height reader; `eth_blockNumber` on the catalog `http` port unless the client needs more (Avalanche-style). */
    val heightProbe: () -> HostNodeHeightProbe = { EvmNodeHeightProbe() },
    val logName: String = network.value,
    /** Resolver of the network's snapshot types (see network.yml `snapshotTypes`); null when it never has one. */
    val snapshot: (() -> SnapshotResolver)? = null,
    /** Artifact URL rule of clients.yml entries that are not plain `{tag}` templates (`github-asset:`, `gethstore`, Kaia's packages). */
    val artifactUrls: ((GitHubReleaseClient) -> ClientArtifactUrlResolver)? = null,
    /**
     * `network.yml` literal binding key → the unit flags that apply it (`EvmCatalogTest` compares the binding value with
     * the rendered flag). Bindings that are not flags (config files, Go env) are tested by their own network test.
     */
    val literalFlags: Map<String, List<String>> = emptyMap(),
    /** `LimitNOFILE` of the units (bindings `limit-nofile`). */
    val nofile: Int = EvmCapacity.NOFILE,
    /**
     * Wizard install options (free text, e.g. Monad's TrieDB block device) that travel to the agent as
     * `--toolkit-opt-<key>=<value>` ([EvmLaunchOptions.extra]) and into a scripted snapshot's URL parameters.
     */
    val optionKeys: List<String> = emptyList(),
) : ScriptedSnapshot
{
    override val snapshotNetwork: String get() = network.value

    override fun snapshotBinaries(envId: String): List<EvmBinary> = recipe.binaries(env(envId))

    override fun snapshotScript(ref: EvmSnapshotRef, paths: EvmSnapshotPaths): String? = recipe.snapshotScript(ref, paths)

    fun env(raw: String): EvmEnv = envs.firstOrNull { it.id == raw.trim().lowercase() } ?: envs.first()

    fun port(env: EvmEnv, role: String): Int =
        env.basePort + (portOffsets[role] ?: error("${network.value} has no port role '$role'"))

    fun ports(env: EvmEnv): Map<String, Int> = portOffsets.mapValues { env.basePort + it.value }

    /** Primary unit log, relative to node_dir (`requirements.logFile` of clients.yml). */
    fun relativeLog(): String = "logs/$logName.log"

    /** Log of a companion unit (consensus client). */
    fun relativeLog(role: String): String = "logs/$logName-$role.log"
}
