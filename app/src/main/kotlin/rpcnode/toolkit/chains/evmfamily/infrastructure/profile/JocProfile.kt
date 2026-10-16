package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmRecipe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.clients.application.version.GitHubAssetUrlResolver
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.NethermindFlags

/** Japan Open Chain: Clique PoA, Nethermind with its built-in `joc-*` configs (Merge off: no consensus client, no Engine API). */
object JocProfile
{
    private object Recipe : EvmRecipe
    {
        override fun binaries(env: EvmEnv) = listOf(NETHERMIND_BUNDLE)

        override fun units(ctx: EvmStartContext): EvmUnits =
            EvmUnits(
                primary = EvmUnitBodies.render(
                    "joc",
                    NethermindFlags.unit(ctx, ctx.env.chain, "Japan Open Chain Nethermind (${ctx.env.id}) — RpcNode", withEngine = false),
                ),
            )
    }

    val PROFILE = EvmProfile(
        network = NetworkId.JOC,
        recipe = Recipe,
        envs = listOf(
            EvmEnv("mainnet", "joc-mainnet", 81, 41780),
            EvmEnv("testnet", "joc-testnet", 10081, 41790),
        ),
        portOffsets = mapOf("http" to 0, "ws" to 1, "p2p" to 2, "metrics" to 4),
        artifactUrls = { GitHubAssetUrlResolver(it) },
        diskRoles = listOf("execution"),
        literalFlags = NETHERMIND_LITERALS,
    )
}
