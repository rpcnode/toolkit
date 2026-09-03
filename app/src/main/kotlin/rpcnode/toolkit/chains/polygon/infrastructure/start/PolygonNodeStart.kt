package rpcnode.toolkit.chains.polygon.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.polygon.infrastructure.PolygonClusters
import rpcnode.toolkit.chains.polygon.infrastructure.polygonNodeMode
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Bor + Heimdall-v2. Launch args encode mode + disk roles for
 * [rpcnode.toolkit.chains.polygon.infrastructure.proc.PolygonNodeProcessStarter].
 */
class PolygonNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.POLYGON

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = ctx.env.trim().lowercase().ifEmpty { "mainnet" }
        val cluster = PolygonClusters.lookup(env)
        val archive = polygonNodeMode(ctx.installOptionsJson) == "archive"
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val borRoot = layout?.roles?.firstOrNull { it.id == "bor" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val borDatadir = if (borRoot.isNotEmpty())
        {
            "$borRoot/datadir"
        }
        else
        {
            ""
        }
        val heimdall = layout?.roles?.firstOrNull { it.id == "heimdall" }?.dir?.trim().orEmpty()
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-archive=${if (archive) "1" else "0"}",
            "--toolkit-chain-id=${cluster.chainId}",
            "--toolkit-eth-rpc=${cluster.ethRpcUrl}",
        )
        if (borDatadir.isNotEmpty())
        {
            args += "--toolkit-bor=$borDatadir"
        }
        if (heimdall.isNotEmpty())
        {
            args += "--toolkit-heimdall=$heimdall"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bor",
                args = args,
                extractArchiveGlob = "bor-*.deb",
                normalizeDir = null,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
