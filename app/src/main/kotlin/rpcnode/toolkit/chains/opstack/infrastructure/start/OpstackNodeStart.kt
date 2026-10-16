package rpcnode.toolkit.chains.opstack.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.arb.infrastructure.arbL1FromInstallOptions
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackInstallOptions
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackNetworkSpec
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * One class for every OP Stack network of the family: the spec says which. Launch args encode env, node type, snapshot choice,
 * the disk roles and the L1 parent for [rpcnode.toolkit.chains.opstack.infrastructure.proc.OpstackNodeProcessStarter]. The L1
 * install options (`l1_rpc`, `l1_beacon`) are the same as Arbitrum's / Optimism's.
 */
class OpstackNodeStart(private val spec: OpstackNetworkSpec) : ChainNodeStart
{
    override val networkId: NetworkId = spec.network

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = spec.normalizeEnv(ctx.env)
        val options = OpstackInstallOptions.fromJson(ctx.installOptionsJson, spec.network)
        val l1 = arbL1FromInstallOptions(ctx.installOptionsJson, env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        fun role(id: String) = layout?.roles?.firstOrNull { it.id == id }?.dir?.trim().orEmpty()
        val execution = role("execution").ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-mode=${options.node}",
            "--toolkit-restore=${options.restore}",
            "--toolkit-l1-rpc=${l1.rpc}",
            "--toolkit-l1-beacon=${l1.beacon}",
        )
        if (options.snapshotUrl.isNotEmpty())
        {
            args += "--toolkit-snapshot-url=${options.snapshotUrl}"
        }
        if (execution.isNotEmpty())
        {
            args += "--toolkit-execution=$execution"
        }
        role("consensus").takeIf { it.isNotEmpty() }?.let { args += "--toolkit-consensus=$it" }
        role("snapshots").takeIf { it.isNotEmpty() }?.let { args += "--toolkit-snapshots=$it" }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = spec.runScript,
                args = args,
                normalizeDir = null,
                logFile = spec.relativeLog,
            ),
            height = NodeHeightSpec(
                kind = "eth_rpc",
                portRole = "http",
            ),
        )
    }
}
