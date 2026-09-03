package rpcnode.toolkit.chains.solana.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.solana.infrastructure.solanaNodeMode
import rpcnode.toolkit.chains.solana.infrastructure.solanaRpcTuning
import rpcnode.toolkit.chains.solana.infrastructure.solanaSysctlTuning
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Non-voting Agave RPC. Launch args encode disk roles, mode, RPC + sysctl tuning for
 * [rpcnode.toolkit.chains.solana.infrastructure.proc.SolanaNodeProcessStarter].
 */
class SolanaNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.SOLANA

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = ctx.env.trim().lowercase().ifEmpty { "mainnet" }
        val archive = solanaNodeMode(ctx.installOptionsJson) == "archive"
        val tuning = solanaRpcTuning(ctx.installOptionsJson)
        val sysctl = solanaSysctlTuning(ctx.installOptionsJson)
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        val ledger = layout?.roles?.firstOrNull { it.id == "ledger" }?.dir?.trim().orEmpty()
            .ifEmpty { layout?.ledgerDir?.trim().orEmpty() }
        val accounts = layout?.roles?.firstOrNull { it.id == "accounts" }?.dir?.trim().orEmpty()
            .ifEmpty { layout?.accountsDir?.trim().orEmpty() }
        val snapshots = layout?.roles?.firstOrNull { it.id == "snapshots" }?.dir?.trim().orEmpty()
            .ifEmpty { layout?.snapshotsDir?.trim().orEmpty() }
        val args = mutableListOf(
            "--toolkit-env=$env",
            "--toolkit-archive=${if (archive) "1" else "0"}",
            "--toolkit-rpc-threads=${tuning.rpcThreads}",
            "--toolkit-rpc-pubsub-worker-threads=${tuning.rpcPubsubWorkerThreads}",
            "--toolkit-rpc-pubsub-max-active-subscriptions=${tuning.rpcPubsubMaxActiveSubscriptions}",
            "--toolkit-rpc-max-request-body-size=${tuning.rpcMaxRequestBodySize}",
            "--toolkit-limit-nofile=${tuning.limitNofile}",
            "--toolkit-sysctl-rmem-default=${sysctl.rmemDefault}",
            "--toolkit-sysctl-rmem-max=${sysctl.rmemMax}",
            "--toolkit-sysctl-wmem-default=${sysctl.wmemDefault}",
            "--toolkit-sysctl-wmem-max=${sysctl.wmemMax}",
            "--toolkit-sysctl-vm-max-map-count=${sysctl.vmMaxMapCount}",
            "--toolkit-sysctl-fs-nr-open=${sysctl.fsNrOpen}",
        )
        if (ledger.isNotEmpty()) args += "--toolkit-ledger=$ledger"
        if (accounts.isNotEmpty()) args += "--toolkit-accounts=$accounts"
        if (snapshots.isNotEmpty()) args += "--toolkit-snapshots=$snapshots"
        return ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = "agave-validator",
                args = args,
                extractArchiveGlob = "solana-release-*.tar.bz2",
                normalizeDir = null,
                logFile = "logs/validator.log",
            ),
            height = NodeHeightSpec(
                kind = "solana_rpc",
                portRole = "http",
            ),
        )
    }
}
