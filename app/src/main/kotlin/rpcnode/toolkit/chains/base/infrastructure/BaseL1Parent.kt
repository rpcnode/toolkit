package rpcnode.toolkit.chains.base.infrastructure

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves Ethereum L1 execution RPC + beacon for Base consensus.
 * Order: explicit override (Start install_options / launch args) → `RPCNODE_L1_*` env →
 * public defaults (mainnet + sepolia) → local ethereum on this host (last resort).
 */
object BaseL1Parent
{
    const val ENV_RPC = "RPCNODE_L1_RPC_URL"
    const val ENV_BEACON = "RPCNODE_L1_BEACON_URL"

    const val SEPOLIA_RPC = "https://ethereum-sepolia-rpc.publicnode.com"
    const val SEPOLIA_BEACON = "https://ethereum-sepolia-beacon-api.publicnode.com"
    const val MAINNET_RPC = "https://ethereum-rpc.publicnode.com"
    const val MAINNET_BEACON = "https://ethereum-beacon-api.publicnode.com"

    data class Endpoints(val rpc: String, val beacon: String)

    sealed interface Result
    {
        data class Ok(val endpoints: Endpoints) : Result
        data class Missing(val detail: String) : Result
    }

    fun publicDefaults(baseEnv: String): Endpoints?
    {
        return when (BaseClusters.l1Env(baseEnv))
        {
            "sepolia" -> Endpoints(rpc = SEPOLIA_RPC, beacon = SEPOLIA_BEACON)
            "mainnet" -> Endpoints(rpc = MAINNET_RPC, beacon = MAINNET_BEACON)
            else -> null
        }
    }

    fun resolve(
        baseEnv: String,
        rpcOverride: String = "",
        beaconOverride: String = "",
    ): Result
    {
        val l1 = BaseClusters.l1Env(baseEnv)
        val local = localEthereum(l1)
        val pub = publicDefaults(baseEnv)

        val rpc = rpcOverride.trim()
            .ifEmpty { System.getenv(ENV_RPC)?.trim().orEmpty() }
            .ifEmpty { pub?.rpc.orEmpty() }
            .ifEmpty { local?.rpc.orEmpty() }
        val beacon = beaconOverride.trim()
            .ifEmpty { System.getenv(ENV_BEACON)?.trim().orEmpty() }
            .ifEmpty { pub?.beacon.orEmpty() }
            .ifEmpty { local?.beacon.orEmpty() }
        if (rpc.isEmpty())
        {
            return Result.Missing(
                "no ethereum $l1 execution RPC — set Start l1_rpc or $ENV_RPC",
            )
        }
        if (beacon.isEmpty())
        {
            return Result.Missing(
                "no ethereum $l1 beacon — set Start l1_beacon or $ENV_BEACON",
            )
        }
        return Result.Ok(Endpoints(rpc = rpc, beacon = beacon))
    }

    private fun localEthereum(l1Env: String): Endpoints?
    {
        val etc = Path.of("/etc/ethereum", l1Env)
        if (!Files.isRegularFile(etc.resolve("jwt.hex")) &&
            !Files.isRegularFile(etc.resolve("toolkit.env"))
        )
        {
            return null
        }
        // Catalog ports from ethereum clients.yml (mainnet / sepolia).
        val http = if (l1Env == "sepolia") 8546 else 8545
        val beacon = if (l1Env == "sepolia") 5053 else 5052
        return Endpoints(
            rpc = "http://127.0.0.1:$http",
            beacon = "http://127.0.0.1:$beacon",
        )
    }
}
