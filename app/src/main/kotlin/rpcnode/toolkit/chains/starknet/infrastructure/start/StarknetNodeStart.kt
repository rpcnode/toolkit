package rpcnode.toolkit.chains.starknet.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetChain
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetOptions
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetParentChain
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Starknet Juno full node. Launch args encode env + the state disk role + snapshot flavor + prune window + the Ethereum L1
 * WebSocket for [rpcnode.toolkit.chains.starknet.infrastructure.proc.StarknetNodeProcessStarter].
 * Install options: `snapshot` (pruned|full), `prune_blocks`, `l1_rpc` (a ws:// or wss:// URL).
 */
class StarknetNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.STARKNET

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = StarknetChain.lookup(ctx.env)
        val options = StarknetOptions.parse(ctx.installOptionsJson)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val state = layout?.roles?.firstOrNull { it.id == "state" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val args = mutableListOf(
            "--toolkit-env=${env.id}",
            "--toolkit-snapshot=${options.snapshot}",
            "--toolkit-l1-rpc=${StarknetParentChain.resolve(env, options.ethNode)}",
        )
        options.pruneWindow()?.let { args += "--toolkit-prune=$it" }
        if (state.isNotEmpty())
        {
            args += "--toolkit-state=$state"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/juno",
                args = args,
                extractArchiveGlob = "juno-*.zip",
                normalizeDir = null,
            ),
            height = NodeHeightSpec(
                kind = "starknet_rpc",
                portRole = "http",
            ),
        )
    }
}
