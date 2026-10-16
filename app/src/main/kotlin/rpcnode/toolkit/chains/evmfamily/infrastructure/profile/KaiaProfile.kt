package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmRecipe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnit
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.KaiaPackageUrlResolver
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.KaiaSnapshotResolver

/**
 * Kaia (ex Klaytn) Endpoint Node: `ken --mainnet|--kairos` with the flag set of the official `kend.conf` (VERIFIED at v2.2.2),
 * run directly (the `kend` wrapper daemonises and would hide the PID from systemd). The chain DB comes from the official
 * live-pruned snapshot (stream extract); `--state.live-pruning` matches it (harmless on a fresh DB, VERIFIED in node/cn).
 * `--prometheusport` binds ALL interfaces (the exporter does `ListenAndServe(":port")`): firewall it.
 */
object KaiaProfile
{
    const val RPC_API = "klay,eth,net,web3"
    const val CONCURRENCY_LIMIT = 3000
    const val MAX_CONNECTIONS = 10
    const val TXPOOL_SLOTS = 4096

    fun flags(ctx: EvmStartContext): List<String> = listOf(
        "--${ctx.env.chain}",
        "--datadir ${ctx.dir("chaindata")}",
        "--port ${ctx.port("p2p")}",
        "--subport ${ctx.port("subport")}",
        "--multichannel",
        "--srvtype http",
        "--syncmode full",
        "--state.live-pruning",
        "--maxconnections $MAX_CONNECTIONS",
        "--verbosity 3",
        "--txpool.exec-slots.all $TXPOOL_SLOTS",
        "--txpool.nonexec-slots.all $TXPOOL_SLOTS",
        "--txpool.exec-slots.account $TXPOOL_SLOTS",
        "--txpool.nonexec-slots.account $TXPOOL_SLOTS",
        "--txpool.lifetime 30m",
        "--rpc",
        "--rpcaddr 0.0.0.0",
        "--rpcport ${ctx.port("http")}",
        "--rpcapi $RPC_API",
        "--rpccorsdomain *",
        "--rpcvhosts *",
        "--rpc.concurrencylimit $CONCURRENCY_LIMIT",
        "--rpcreadtimeout 30",
        "--rpcwritetimeout 30",
        "--rpcidletimeout 120",
        "--rpcexecutiontimeout 30",
        "--ws",
        "--wsaddr 0.0.0.0",
        "--wsport ${ctx.port("ws")}",
        "--wsapi $RPC_API",
        "--wsorigins *",
        "--metrics",
        "--prometheus",
        "--prometheusport ${ctx.port("metrics")}",
    )

    private object Recipe : EvmRecipe
    {
        // ken-linux-amd64/bin/ken inside ken-<tag>-0-linux-amd64.tar.gz (the arm64 tarball has the same layout)
        override fun binaries(env: EvmEnv) = listOf(EvmBinary("ken", "ken-linux-*.tar.gz", EvmBinary.Kind.TAR_MEMBER))

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val unit = EvmUnit(
                description = "Kaia ken (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("ken").toString()),
                flags = flags(ctx),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog()),
                // ken (geth fork) shuts down cleanly on SIGINT and SIGTERM; SIGINT as upstream's own service files
                killSignal = "SIGINT",
                timeoutStopSec = 300,
            )
            return EvmUnits(primary = EvmUnitBodies.render("kaia", unit))
        }
    }

    val PROFILE = EvmProfile(
        network = NetworkId.KAIA,
        recipe = Recipe,
        envs = listOf(
            EvmEnv("mainnet", "mainnet", 8217, 41810),
            EvmEnv("kairos", "kairos", 1001, 41820),
        ),
        portOffsets = mapOf("http" to 0, "ws" to 1, "p2p" to 2, "metrics" to 4, "subport" to 8),
        diskRoles = listOf("chaindata"),
        snapshot = { KaiaSnapshotResolver() },
        artifactUrls = { KaiaPackageUrlResolver() },
        literalFlags = mapOf(
            "rpc-api" to listOf("--rpcapi", "--wsapi"),
            "rpc-concurrencylimit" to listOf("--rpc.concurrencylimit"),
            "maxconnections" to listOf("--maxconnections"),
        ),
    )
}
