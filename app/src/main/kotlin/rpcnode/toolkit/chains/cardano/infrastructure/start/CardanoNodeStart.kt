package rpcnode.toolkit.chains.cardano.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoClusters
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoIndexerMode
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoUnitBodies
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Cardano relay. Launch args carry the env, the blockchain disk role and the optional indexer (install option `indexer`
 * + the `indexer` disk role: db-sync ledger state and PostgreSQL tablespace); the height comes from the Prometheus port.
 */
class CardanoNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.CARDANO

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = CardanoClusters.normalizeEnv(ctx.env)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val blockchain = layout?.roles?.firstOrNull { it.id == "blockchain" }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
        val indexerDir = layout?.roles?.firstOrNull { it.id == "indexer" }?.dir?.trim().orEmpty()
        val mode = CardanoIndexerMode.fromJson(ctx.installOptionsJson)
        val args = mutableListOf("--toolkit-env=$env", "--toolkit-indexer=${mode.id}")
        if (blockchain.isNotEmpty())
        {
            args += "--toolkit-blockchain=$blockchain"
        }
        if (indexerDir.isNotEmpty())
        {
            args += "--toolkit-indexer-dir=$indexerDir"
        }
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "bin/cardano-node",
                args = args,
                extractArchiveGlob = "cardano-node-linux-*.tar.gz",
                normalizeDir = null,
                logFile = CardanoUnitBodies.RELATIVE_LOG,
            ),
            height = NodeHeightSpec(
                kind = "cardano_metrics",
                portRole = "metrics",
            ),
        )
    }
}
