package rpcnode.toolkit.chains.xrpl.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplClusters
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplHistory
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplUnitBodies
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * XRPL stock xrpld. Launch args encode env + ledger disk + history for
 * [rpcnode.toolkit.chains.xrpl.infrastructure.proc.XrplNodeProcessStarter].
 */
class XrplNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.XRPL

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = XrplClusters.normalizeEnv(ctx.env)
        val history = XrplHistory.fromJson(ctx.installOptionsJson)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val ledger = layout?.roles?.firstOrNull { it.id == "ledger" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-history=${history.mode}",
        )
        if (ledger.isNotEmpty())
        {
            args += "--toolkit-ledger=$ledger"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/${XrplUnitBodies.BIN_NAME}",
                args = args,
                extractArchiveGlob = "xrpld-*.deb",
                normalizeDir = null,
                logFile = XrplUnitBodies.RELATIVE_LOG,
            ),
            height = NodeHeightSpec(
                kind = "xrpl_rpc",
                portRole = "http",
            ),
        )
    }
}
