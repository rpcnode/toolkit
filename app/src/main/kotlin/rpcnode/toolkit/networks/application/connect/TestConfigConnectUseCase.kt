package rpcnode.toolkit.networks.application.connect

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

/**
 * Panel-side live probe for Start `clientConfig.bindings[].testConnect`.
 * Kinds are declared in chains/<id>/network.yml (`eth_rpc`, `beacon_genesis`).
 */
class TestConfigConnectUseCase(
    private val http: SimpleHttp = SimpleHttp(),
)
{
    sealed interface Result
    {
        data class Ok(val detail: String) : Result
        data class Failed(val detail: String) : Result
        data object BadKind : Result
        data object BadUrl : Result
    }

    suspend operator fun invoke(kind: String, url: String): Result
    {
        val endpoint = url.trim()
        if (endpoint.isEmpty() || (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")))
        {
            return Result.BadUrl
        }
        return when (kind.trim().lowercase())
        {
            "eth_rpc" -> probeEthRpc(endpoint)
            "beacon_genesis" -> probeBeaconGenesis(endpoint)
            else -> Result.BadKind
        }
    }

    private suspend fun probeEthRpc(url: String): Result
    {
        val height = EthereumEthRpc.blockNumber(http, url)
            ?: return Result.Failed("eth_blockNumber failed — unreachable or non-JSON-RPC response")
        return Result.Ok("eth_blockNumber ok · height $height")
    }

    private suspend fun probeBeaconGenesis(url: String): Result
    {
        val base = url.trim().trimEnd('/')
        val body = http.getText("$base/eth/v1/beacon/genesis", accept = "application/json")
            ?: return Result.Failed("GET /eth/v1/beacon/genesis failed — unreachable or non-2xx")
        val genesis = runCatching {
            json.parseToJsonElement(body).jsonObject["data"]
                ?.jsonObject
                ?.get("genesis_time")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()?.trim().orEmpty()
        if (genesis.isEmpty())
        {
            return Result.Failed("beacon genesis JSON missing data.genesis_time")
        }
        return Result.Ok("beacon genesis ok · genesis_time=$genesis")
    }

    companion object
    {
        private val json = Json { ignoreUnknownKeys = true }
    }
}
