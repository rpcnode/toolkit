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
 * Fluent (blended-execution L2, reth fork): ONE binary, `fluent node --chain fluent-mainnet|fluent-testnet`. Beyond devp2p
 * it follows Fluent's public websocket endpoint (wss://rpc[.testnet].fluent.xyz, VERIFIED in main.rs), so it depends on it.
 * Snapshots: `fluent download` (legacy path: cdn.fluent.xyz/snapshots/<chain id>/latest.txt) after `fluent init`.
 * Reth flags VERIFIED present (reth_ethereum_cli on fluentlabs-xyz/reth). x86_64 only: v1.5.1 has no aarch64 asset.
 */
object FluentProfile
{
    const val HTTP_API = "eth,net,web3,rpc"

    fun flags(ctx: EvmStartContext): List<String> = buildList {
        add("--chain=${ctx.env.chain}")
        add("--datadir=${ctx.dir("data").resolve("data")}")
        addAll(RethFlags.publicRpc(ctx.port("http"), ctx.port("ws"), HTTP_API))
        addAll(RethFlags.p2p(ctx.port("p2p")))
        addAll(RethFlags.capacity())
        addAll(RethFlags.metrics(ctx.port("metrics")))
        add("--ipcdisable")
    }

    private object Recipe : EvmRecipe
    {
        override fun binaries(env: EvmEnv) = listOf(EvmBinary("fluent", "fluent-*.tar.gz", EvmBinary.Kind.TAR_MEMBER))

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val unit = EvmUnit(
                description = "Fluent node (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("fluent").toString(), "node"),
                flags = flags(ctx),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog()),
                timeoutStopSec = 300,
            )
            return EvmUnits(primary = EvmUnitBodies.render("fluent", unit))
        }

        override fun snapshotScript(ref: EvmSnapshotRef, paths: EvmSnapshotPaths): String =
            EvmSnapshotScripts.render(
                "fluent",
                paths,
                units = listOf(EvmUnitBodies.unitName("fluent", ref.env)),
                vars = mapOf(
                    "fluent" to EvmSnapshotScripts.q(paths.bins.getValue("fluent").toString()),
                    "datadir" to EvmSnapshotScripts.q(paths.root.resolve("data").toString()),
                    "chain" to if (ref.env == "mainnet") "fluent-mainnet" else "fluent-testnet",
                ),
            )
    }

    val PROFILE = EvmProfile(
        network = NetworkId.FLUENT,
        recipe = Recipe,
        envs = listOf(
            EvmEnv("mainnet", "fluent-mainnet", 25363, 41480),
            EvmEnv("testnet", "fluent-testnet", 20994, 41490),
        ),
        portOffsets = mapOf("http" to 0, "ws" to 1, "p2p" to 2, "metrics" to 4),
        diskRoles = listOf("data"),
        snapshot = {
            EvmSnapshotSentinelResolver(
                "fluent",
                mapOf("mainnet" to setOf("official"), "testnet" to setOf("official")),
                defaultKind = "official",
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
