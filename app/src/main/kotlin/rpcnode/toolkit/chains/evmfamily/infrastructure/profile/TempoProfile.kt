package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmRecipe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotPaths
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotScripts
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnit
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.EvmSnapshotSentinelResolver
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.RethFlags

/**
 * Tempo (payments L1, reth-based): an RPC node is `tempo node --follow` — a trustless follower that verifies finalization
 * certificates and re-executes every block (no validator key, no Engine API, no consensus companion). Flags VERIFIED at v1.16.0.
 * Bare `--follow` follows the chain's own upstream (mainnet wss://rpc.presto.tempo.xyz, moderato wss://rpc.moderato.tempo.xyz).
 * Beyond the drafts: the follower also binds a consensus-metrics server (127.0.0.1:8001), reth's authrpc (8551) and an IPC
 * socket (/tmp/reth.ipc) by default, so all three are pinned to catalog ports / disabled (two networks on one host).
 */
object TempoProfile
{
    const val HTTP_API = "eth,net,web3"

    fun flags(ctx: EvmStartContext): List<String> = buildList {
        add("--follow")
        add("--chain ${ctx.env.chain}")
        add("--datadir ${ctx.dir("data").resolve("data")}")
        addAll(RethFlags.publicRpc(ctx.port("http"), ctx.port("ws"), HTTP_API))
        addAll(RethFlags.p2p(ctx.port("p2p")))
        addAll(RethFlags.capacity())
        addAll(RethFlags.metrics(ctx.port("metrics")))
        add("--authrpc.port ${ctx.port("authrpc")}")
        add("--consensus.metrics-address 127.0.0.1:${ctx.port("consensus_metrics")}")
        add("--ipcdisable")
        add("--color never")
    }

    private object Recipe : EvmRecipe
    {
        // the release tarball holds ONE file named tempo-<tag>-<target>
        override fun binaries(env: EvmEnv) = listOf(EvmBinary("tempo", "tempo-*.tar.gz", EvmBinary.Kind.TAR_MEMBER, member = "tempo*"))

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val unit = EvmUnit(
                description = "Tempo tempo node --follow (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("tempo").toString(), "node"),
                flags = flags(ctx),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog()),
                timeoutStopSec = 300,
            )
            return EvmUnits(primary = EvmUnitBodies.render("tempo", unit))
        }

        override fun snapshotScript(ref: EvmSnapshotRef, paths: EvmSnapshotPaths): String =
            EvmSnapshotScripts.render(
                "tempo",
                paths,
                units = listOf(EvmUnitBodies.unitName("tempo", ref.env)),
                vars = mapOf(
                    "tempo" to EvmSnapshotScripts.q(paths.bins.getValue("tempo").toString()),
                    "datadir" to EvmSnapshotScripts.q(paths.root.resolve("data").toString()),
                    "chain" to ref.env.let { if (it == "mainnet") "mainnet" else "moderato" },
                    "profile" to ref.kind,
                ),
            )
    }

    val PROFILE = EvmProfile(
        network = NetworkId.TEMPO,
        recipe = Recipe,
        envs = listOf(
            EvmEnv("mainnet", "mainnet", 4217, 42590),
            EvmEnv("moderato", "moderato", 42431, 42600),
        ),
        portOffsets = mapOf("http" to 0, "ws" to 1, "p2p" to 2, "authrpc" to 3, "metrics" to 4, "consensus_metrics" to 5),
        diskRoles = listOf("data"),
        snapshot = {
            EvmSnapshotSentinelResolver(
                "tempo",
                mapOf("mainnet" to setOf("archive", "full", "minimal"), "moderato" to setOf("archive", "minimal")),
                defaultKind = "archive",
            )
        },
        literalFlags = mapOf(
            "http-api" to listOf("--http.api", "--ws.api"),
            "rpc-max-connections" to listOf("--rpc.max-connections"),
            "rpc-max-response-size" to listOf("--rpc.max-response-size"),
            "rpc-max-blocks-per-filter" to listOf("--rpc.max-blocks-per-filter"),
        ),
    )
}
