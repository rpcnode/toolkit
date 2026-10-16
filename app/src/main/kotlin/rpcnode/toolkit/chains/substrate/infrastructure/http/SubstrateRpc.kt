package rpcnode.toolkit.chains.substrate.infrastructure.http

import rpcnode.toolkit.chains.nonevm.infrastructure.HeightJson
import rpcnode.toolkit.chains.nonevm.infrastructure.HeightReader
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

/**
 * Substrate JSON-RPC over HTTP (the RPC port serves HTTP and WS). `chain_getHeader` without params is the best (not
 * finalized) head — the public tip uses the same call, so a lag is like-for-like. Both methods are in the `safe` set.
 */
object SubstrateRpc
{
    /** `result.number` is a hex string (`"0x1fdeb31"`). */
    val HEADER = HeightReader.JsonRpc(method = "chain_getHeader", result = listOf("number"))

    /** `result.{isSyncing, peers, shouldHavePeers}` of `system_health`. */
    data class Health(val isSyncing: Boolean, val peers: Int)

    fun parseHealth(body: String?): Health?
    {
        val root = HeightJson.root(body)
        val syncing = HeightJson.bool(root, listOf("result", "isSyncing")) ?: return null
        val peers = HeightJson.long(root, listOf("result", "peers"))?.toInt() ?: 0
        return Health(isSyncing = syncing, peers = peers)
    }

    suspend fun health(http: SimpleHttp, url: String): Health? =
        parseHealth(http.postJson(url, """{"jsonrpc":"2.0","id":1,"method":"system_health","params":[]}"""))
}
