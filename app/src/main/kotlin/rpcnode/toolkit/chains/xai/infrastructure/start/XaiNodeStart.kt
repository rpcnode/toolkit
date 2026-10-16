package rpcnode.toolkit.chains.xai.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.xai.infrastructure.XaiChain
import rpcnode.toolkit.chains.xai.infrastructure.XaiParentChain
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Xai Nitro full node. Launch args encode the Arbitrum One parent RPC + the execution disk role for
 * [rpcnode.toolkit.chains.xai.infrastructure.proc.XaiNodeProcessStarter]. The only install option is `l1_rpc`
 * (there is no snapshot flavor and no beacon).
 */
class XaiNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.XAI

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val execution = layout?.roles?.firstOrNull { it.id == "execution" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf(
            "--toolkit-env=${XaiChain.normalizeEnv(ctx.env)}",
            "--toolkit-l1-rpc=${XaiParentChain.fromInstallOptions(ctx.installOptionsJson)}",
        )
        if (execution.isNotEmpty())
        {
            args += "--toolkit-execution=$execution"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/nitro",
                args = args,
                extractArchiveGlob = "nitro-*.tar.gz",
                normalizeDir = null,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
