package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmRecipe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.clients.application.version.GitHubAssetUrlResolver
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.LighthouseFlags
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.NethermindFlags

/** The Nethermind release zip: the whole single-file app plus `configs/` is unpacked into `bin/`. */
internal val NETHERMIND_BUNDLE = EvmBinary("nethermind", "nethermind-linux-*.zip", EvmBinary.Kind.ZIP_BUNDLE)

/** Nethermind capacity bindings of network.yml → the flags that apply them. */
internal val NETHERMIND_LITERALS = mapOf(
    "json-rpc-modules" to listOf("--JsonRpc.EnabledModules"),
    "json-rpc-max-batch-size" to listOf("--JsonRpc.MaxBatchSize"),
    "json-rpc-request-queue-limit" to listOf("--JsonRpc.RequestQueueLimit"),
    "max-active-peers" to listOf("--Network.MaxActivePeers"),
)

/** Gnosis Chain: Nethermind (execution) + Lighthouse (consensus, `--network gnosis|chiado`). No snapshot: snap sync + checkpoint sync. */
object GnosisProfile
{
    private object Recipe : EvmRecipe
    {
        override val usesJwt = true

        override fun binaries(env: EvmEnv) = listOf(
            NETHERMIND_BUNDLE,
            EvmBinary("lighthouse", "lighthouse-*.tar.gz", EvmBinary.Kind.TAR_MEMBER),
        )

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val label = ctx.env.fact("label")
            val el = NethermindFlags.unit(ctx, ctx.env.chain, "Gnosis Nethermind ($label) — RpcNode", withEngine = true)
            val cl = LighthouseFlags.unit(ctx, ctx.env.chain, ctx.env.fact("checkpoint"), "Gnosis Lighthouse ($label) — RpcNode")
            return EvmUnits(
                primary = EvmUnitBodies.render("gnosis", el),
                companions = listOf(ctx.companionUnit("consensus") to EvmUnitBodies.render("gnosis", cl, EvmUnitBodies.COMPANION_TEMPLATE)),
            )
        }
    }

    val PROFILE = EvmProfile(
        network = NetworkId.GNOSIS,
        recipe = Recipe,
        envs = listOf(
            EvmEnv("mainnet", "gnosis", 100, 41570, mapOf("label" to "mainnet", "checkpoint" to "https://checkpoint.gnosischain.com")),
            EvmEnv("chiado", "chiado", 10200, 41580, mapOf("label" to "chiado", "checkpoint" to "https://checkpoint.chiadochain.net")),
        ),
        portOffsets = mapOf(
            "http" to 0, "ws" to 1, "p2p" to 2, "engine" to 3, "metrics" to 4,
            "beacon" to 5, "consensus_p2p" to 6, "consensus_quic" to 7, "consensus_metrics" to 8,
        ),
        artifactUrls = { GitHubAssetUrlResolver(it) },
        diskRoles = listOf("execution", "consensus"),
        literalFlags = NETHERMIND_LITERALS,
    )
}
