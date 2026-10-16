package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnit

/**
 * Lighthouse beacon node for a chain it ships (`--network gnosis|chiado`), flags VERIFIED at v8.2.3. The beacon API and
 * metrics stay on 127.0.0.1; QUIC is pinned (it would otherwise land on port+1); the Engine API is the shared JWT.
 */
object LighthouseFlags
{
    fun unit(ctx: EvmStartContext, network: String, checkpointUrl: String, description: String): EvmUnit =
        EvmUnit(
            description = description,
            command = listOf(ctx.bin("lighthouse").toString(), "bn"),
            flags = listOf(
                "--network $network",
                "--datadir ${ctx.dir("consensus")}",
                "--execution-endpoint http://127.0.0.1:${ctx.port("engine")}",
                "--execution-jwt ${checkNotNull(ctx.jwt)}",
                "--checkpoint-sync-url $checkpointUrl",
                "--http",
                "--http-address 127.0.0.1",
                "--http-port ${ctx.port("beacon")}",
                "--port ${ctx.port("consensus_p2p")}",
                "--quic-port ${ctx.port("consensus_quic")}",
                "--metrics",
                "--metrics-address 127.0.0.1",
                "--metrics-port ${ctx.port("consensus_metrics")}",
            ),
            workDir = ctx.root,
            logFile = ctx.root.resolve(ctx.profile.relativeLog("consensus")),
            after = listOf(ctx.primaryUnit),
            timeoutStopSec = 300,
        )
}
