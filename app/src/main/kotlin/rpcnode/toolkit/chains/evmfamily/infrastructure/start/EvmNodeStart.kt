package rpcnode.toolkit.chains.evmfamily.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmLaunchOptions
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * One class for every EVM-family network: the profile says which. Launch args carry the env, the wizard's `node` /
 * `snapshot` options and the directory of every disk role for [EvmNodeProcessStarter].
 */
class EvmNodeStart(private val profile: EvmProfile) : ChainNodeStart
{
    override val networkId: NetworkId = profile.network

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = profile.env(ctx.env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val dirs = profile.diskRoles.mapNotNull { role ->
            val dir = layout?.roles?.firstOrNull { it.id == role }?.dir?.trim().orEmpty()
            if (dir.isEmpty()) null else role to dir
        }.toMap()
        val options = EvmLaunchOptions(
            env = env.id,
            mode = EvmLaunchOptions.modeFrom(ctx.installOptionsJson),
            snapshot = EvmLaunchOptions.snapshotFrom(ctx.installOptionsJson),
            dirs = dirs,
            extra = EvmLaunchOptions.extraFrom(ctx.installOptionsJson, profile.optionKeys),
        )
        val primary = profile.recipe.binaries(env).first().name
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/$primary",
                args = options.toArgs(),
                extractArchiveGlob = null,
                normalizeDir = null,
                logFile = profile.relativeLog(),
            ),
            height = NodeHeightSpec(kind = "eth_rpc", portRole = "http"),
        )
    }
}
