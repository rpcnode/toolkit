package rpcnode.toolkit.chains.zcash.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.zcash.infrastructure.ZcashCli
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Zcashd: extract ZODL tarball → `zcash/bin/zcashd -datadir=… -conf=… [-testnet|-regtest] -daemon=0`.
 * Run [ZcashNodeProcessStarter] first — it downloads Sapling/Orchard params via zcash-fetch-params.
 */
class ZcashNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.ZCASH

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val conf = ctx.configFile?.trim()?.takeIf { it.isNotEmpty() } ?: "zcash.conf"
        val nodeDir = ctx.nodeDir?.trim()?.takeIf { it.isNotEmpty() }
        val args = if (nodeDir != null)
        {
            ZcashCli.daemonArgs(nodeDir, conf, ctx.env)
        }
        else
        {
            listOf("-conf=$conf", "-daemon=0")
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "zcash/bin/zcashd",
                args = args,
                extractArchiveGlob = "*.tar.gz",
                normalizeDir = "zcash",
            ),
            height = NodeHeightSpec(
                kind = "bitcoin_cli",
                portRole = "rpc",
            ),
        )
    }
}
