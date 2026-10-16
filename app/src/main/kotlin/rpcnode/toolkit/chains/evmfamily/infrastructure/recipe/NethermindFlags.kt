package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

import java.nio.file.Path
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmCapacity
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnit

/**
 * Nethermind 2.x command line (flags VERIFIED against Nethermind.Runner Program.cs + IJsonRpcConfig / INetworkConfig /
 * IMetricsConfig at 2.1.0). JSON-RPC and WebSocket listen on separate public ports with the public modules only; the
 * Engine API (when there is a consensus client) is a separate endpoint on 127.0.0.1 behind the JWT; metrics on 127.0.0.1.
 */
object NethermindFlags
{
    /** `-c gnosis` / `-c joc-mainnet_archive`: the config shipped in the release zip (`configs/<name>.json`). */
    fun config(chain: String, archive: Boolean): String = if (archive) "${chain}_archive" else chain

    fun flags(ctx: EvmStartContext, withEngine: Boolean): List<String> = buildList {
        add("--data-dir ${ctx.dir("execution").resolve("data")}")
        add("--JsonRpc.Enabled true")
        add("--JsonRpc.Host 0.0.0.0")
        add("--JsonRpc.Port ${ctx.port("http")}")
        add("--JsonRpc.WebSocketsPort ${ctx.port("ws")}")
        add("--JsonRpc.EnabledModules ${EvmCapacity.NETHERMIND_MODULES}")
        add("--JsonRpc.MaxBatchSize ${EvmCapacity.NETHERMIND_MAX_BATCH_SIZE}")
        add("--JsonRpc.RequestQueueLimit ${EvmCapacity.NETHERMIND_REQUEST_QUEUE_LIMIT}")
        if (withEngine)
        {
            add("--JsonRpc.EngineHost 127.0.0.1")
            add("--JsonRpc.EnginePort ${ctx.port("engine")}")
            add("--JsonRpc.JwtSecretFile ${checkNotNull(ctx.jwt) { "engine needs a JWT" }}")
        }
        add("--Network.P2PPort ${ctx.port("p2p")}")
        add("--Network.DiscoveryPort ${ctx.port("p2p")}")
        add("--Network.MaxActivePeers ${EvmCapacity.NETHERMIND_MAX_ACTIVE_PEERS}")
        add("--Metrics.Enabled true")
        add("--Metrics.ExposeHost 127.0.0.1")
        add("--Metrics.ExposePort ${ctx.port("metrics")}")
    }

    /**
     * The release is a .NET single-file app that unpacks its native parts at start; under systemd `$HOME` is unset, so
     * the extraction directory is pinned inside node_dir (VERIFIED: build.sh publishes with IncludeAllContentForSelfExtract).
     */
    fun environment(ctx: EvmStartContext): List<String> =
        listOf("DOTNET_BUNDLE_EXTRACT_BASE_DIR=${ctx.toolkitDir.resolve("dotnet")}")

    fun unit(ctx: EvmStartContext, chain: String, description: String, withEngine: Boolean, after: List<String> = emptyList()): EvmUnit
    {
        val bin: Path = ctx.bin("nethermind")
        return EvmUnit(
            description = description,
            command = listOf(bin.toString(), "-c", config(chain, ctx.options.archive)),
            flags = flags(ctx, withEngine),
            // configs/ is resolved next to the executable, not from the cwd; the cwd is only a tidy default
            workDir = bin.parent,
            logFile = ctx.root.resolve(ctx.profile.relativeLog()),
            environment = environment(ctx),
            after = after,
            killSignal = "SIGTERM",
            timeoutStopSec = 300,
        )
    }
}
