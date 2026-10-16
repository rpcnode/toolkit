package rpcnode.toolkit.chains.substrate.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmStartPlan
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateMode
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpec
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan

/**
 * One class for every Substrate network: the spec says which. The node type is the wizard's `snapshot` choice
 * (`pruned` | `archive`) so the snapshot and the unit cannot disagree; the optional REST API is `install_options.indexer`.
 */
class SubstrateNodeStart(private val spec: SubstrateSpec) : ChainNodeStart
{
    override val networkId: NetworkId = spec.network

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = spec.env(ctx.env)
        val mode = modeOf(ctx.installOptionsJson)
        val args = mutableListOf("--toolkit-env=${env.id}", "--toolkit-mode=${mode.id}")
        val state = NonEvmStartPlan.roleDir(ctx, "state")
        if (state.isNotEmpty())
        {
            args += "--toolkit-state=$state"
        }
        NonEvmStartPlan.option(ctx.installOptionsJson, INDEXER_OPTION)?.let { args += "--toolkit-indexer=$it" }
        // the history indexer keeps its PostgreSQL tablespace, Node.js and project on the `indexer` disk role when the host has one
        decodeNodeDiskLayout(ctx.diskLayoutJson)?.roles?.firstOrNull { it.id == "indexer" }?.dir?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { args += "--toolkit-indexer-dir=$it" }
        NonEvmStartPlan.option(ctx.installOptionsJson, INDEXER_START_OPTION)?.let { args += "--toolkit-indexer-start=$it" }
        return NonEvmStartPlan.plan(
            entry = "bin/${spec.binary}",
            logFile = spec.relativeLog,
            heightKind = "substrate_rpc",
            args = args,
        )
    }

    companion object
    {
        const val INDEXER_OPTION = "indexer"
        const val INDEXER_START_OPTION = "indexer_start_block"

        fun modeOf(installOptionsJson: String): SubstrateMode =
            SubstrateMode.parse(NonEvmStartPlan.option(installOptionsJson, "snapshot"))
    }
}
