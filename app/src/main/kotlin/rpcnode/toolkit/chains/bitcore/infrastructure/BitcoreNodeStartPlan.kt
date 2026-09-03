package rpcnode.toolkit.chains.bitcore.infrastructure

import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

object BitcoreNodeStartPlan
{
    fun plan(spec: BitcoreChainSpec, ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val conf = ctx.configFile?.trim()?.takeIf { it.isNotEmpty() } ?: spec.configFile
        val nodeDir = ctx.nodeDir?.trim()?.takeIf { it.isNotEmpty() }
        val args = if (nodeDir != null)
        {
            BitcoreCli.daemonArgs(nodeDir, conf, ctx.env, spec.chainArg)
        }
        else
        {
            listOf("-conf=$conf", "-daemon=0")
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = spec.daemonEntry,
                args = args,
                extractArchiveGlob = "*.tar.gz",
                normalizeDir = spec.normalizeDir,
            ),
            height = NodeHeightSpec(
                kind = "bitcoin_cli",
                portRole = "rpc",
            ),
        )
    }
}
