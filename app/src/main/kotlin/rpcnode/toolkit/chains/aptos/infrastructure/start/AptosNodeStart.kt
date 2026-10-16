package rpcnode.toolkit.chains.aptos.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.aptos.infrastructure.AptosBootstrapMode
import rpcnode.toolkit.chains.aptos.infrastructure.AptosClusters
import rpcnode.toolkit.chains.aptos.infrastructure.AptosHostBinaries
import rpcnode.toolkit.chains.aptos.infrastructure.AptosUnitBodies
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/** Aptos fullnode. Launch args encode env, bootstrap mode and the state disk role. */
class AptosNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.APTOS

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = AptosClusters.normalizeEnv(ctx.env)
        val mode = AptosBootstrapMode.fromJson(ctx.installOptionsJson)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val state = layout?.roles?.firstOrNull { it.id == "state" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf("--toolkit-env=$env", "--toolkit-bootstrap=$mode")
        if (state.isNotEmpty())
        {
            args += "--toolkit-state=$state"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/${AptosHostBinaries.NODE}",
                args = args,
                extractArchiveGlob = AptosHostBinaries.ARCHIVE_GLOB,
                normalizeDir = null,
                logFile = AptosUnitBodies.RELATIVE_LOG,
            ),
            height = NodeHeightSpec(
                kind = "aptos_rest",
                portRole = "http",
            ),
        )
    }
}
