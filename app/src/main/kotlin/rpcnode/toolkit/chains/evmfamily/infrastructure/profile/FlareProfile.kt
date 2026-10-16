package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import java.nio.file.Files
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalancheUnitBodies
import rpcnode.toolkit.chains.avalanche.infrastructure.http.AvalancheNodeHeightProbe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmRecipe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnit
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.FlareBootstrap

/**
 * Flare / Songbird / Coston2: go-flare is an avalanchego fork with the C-Chain EVM built in, so the flags and the
 * C-Chain `config.json` reuse [AvalancheUnitBodies]; what differs is the network id, the zip, the bootstrap lookup
 * and a trimmed `eth-apis` (no debug / tx-pool). No snapshot: the node bootstraps from the Flare bootstrap host.
 */
object FlareProfile
{
    const val ETH_APIS = "eth,eth-filter,net,web3,internal-eth,internal-blockchain,internal-transaction"

    /** C-Chain `config.json` (keys VERIFIED against coreth plugin/evm/config at go-flare v1.14.0). */
    fun cChainConfig(archive: Boolean): String
    {
        val apis = ETH_APIS.split(',').joinToString(", ") { "\"$it\"" }
        return buildString {
            appendLine("{")
            appendLine("  \"eth-apis\": [$apis],")
            appendLine("  \"rpc-gas-cap\": ${AvalancheUnitBodies.RPC_GAS_CAP},")
            appendLine("  \"rpc-tx-fee-cap\": ${AvalancheUnitBodies.RPC_TX_FEE_CAP},")
            appendLine("  \"pruning-enabled\": ${!archive},")
            // an empty archive DB would otherwise try state sync, which an unpruned replay cannot use
            if (archive) appendLine("  \"state-sync-enabled\": false,")
            appendLine("  \"admin-api-enabled\": false,")
            appendLine("  \"local-txs-enabled\": false,")
            appendLine("  \"allow-unfinalized-queries\": false,")
            appendLine("  \"allow-unprotected-txs\": false,")
            appendLine("  \"log-level\": \"info\"")
            appendLine("}")
        }
    }

    fun flags(ctx: EvmStartContext, bootstrap: FlareBootstrap.Peer): List<String> = listOf(
        "--network-id=${ctx.env.chain}",
        "--data-dir=${ctx.root.resolve("data")}",
        "--db-dir=${ctx.dir("blockchain").resolve("db")}",
        "--log-dir=${ctx.logsDir.resolve("avalanchego")}",
        "--http-host=${AvalancheUnitBodies.HTTP_HOST}",
        "--http-port=${ctx.port("http")}",
        "--http-allowed-hosts=${AvalancheUnitBodies.HTTP_ALLOWED_HOSTS}",
        "--staking-port=${ctx.port("p2p")}",
        "--api-admin-enabled=${AvalancheUnitBodies.API_ADMIN_ENABLED}",
        "--index-enabled=${AvalancheUnitBodies.INDEX_ENABLED}",
        "--bootstrap-ips=${bootstrap.ip}",
        "--bootstrap-ids=${bootstrap.nodeId}",
    )

    private object Recipe : EvmRecipe
    {
        override fun binaries(env: EvmEnv) = listOf(EvmBinary("avalanchego", "go-flare-*.zip", EvmBinary.Kind.ZIP_MEMBER))

        override fun prepare(ctx: EvmStartContext): String?
        {
            val peer = FlareBootstrap.resolve(ctx.root, ctx.env.fact("bootstrap"))
                ?: return "Flare bootstrap host ${ctx.env.fact("bootstrap")} did not answer and no earlier answer is cached"
            ctx.scratch["bootstrap.ip"] = peer.ip
            ctx.scratch["bootstrap.id"] = peer.nodeId
            return try
            {
                val config = ctx.root.resolve("data/configs/chains/C/config.json")
                Files.createDirectories(config.parent)
                Files.createDirectories(ctx.dir("blockchain").resolve("db"))
                Files.createDirectories(ctx.logsDir.resolve("avalanchego"))
                Files.writeString(config, cChainConfig(ctx.options.archive))
                null
            }
            catch (e: Exception)
            {
                e.message ?: "write C-Chain config failed"
            }
        }

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val peer = FlareBootstrap.Peer(ctx.scratch.getValue("bootstrap.ip"), ctx.scratch.getValue("bootstrap.id"))
            val unit = EvmUnit(
                description = "Flare go-flare (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("avalanchego").toString()),
                flags = flags(ctx, peer),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog()),
                timeoutStopSec = 90,
            )
            return EvmUnits(primary = EvmUnitBodies.render("flare", unit))
        }
    }

    val PROFILE = EvmProfile(
        network = NetworkId.FLARE,
        recipe = Recipe,
        envs = listOf(
            EvmEnv("flare", "flare", 14, 41450, mapOf("bootstrap" to "flare-bootstrap.flare.network")),
            EvmEnv("songbird", "songbird", 19, 41460, mapOf("bootstrap" to "songbird-bootstrap.flare.network")),
            EvmEnv("coston2", "costwo", 114, 41470, mapOf("bootstrap" to "coston2-bootstrap.flare.network")),
        ),
        portOffsets = mapOf("http" to 0, "p2p" to 2),
        diskRoles = listOf("blockchain"),
        heightProbe = { AvalancheNodeHeightProbe() },
        literalFlags = mapOf(
            "http-host" to listOf("--http-host"),
            "http-allowed-hosts" to listOf("--http-allowed-hosts"),
            "api-admin-enabled" to listOf("--api-admin-enabled"),
            "index-enabled" to listOf("--index-enabled"),
        ),
    )
}
