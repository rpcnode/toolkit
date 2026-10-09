package rpcnode.toolkit.chains.tron.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TronWalletRpcTest
{
    @Test
    fun normalize_strips_jsonrpc_suffix()
    {
        assertEquals(
            "https://example.quiknode.pro/TOKEN",
            TronWalletRpc.normalizeBaseUrl("https://example.quiknode.pro/TOKEN/jsonrpc"),
        )
        assertEquals(
            "http://192.168.0.59:18091",
            TronWalletRpc.normalizeBaseUrl("http://192.168.0.59:18091/"),
        )
    }

    @Test
    fun parse_code_version_from_getnodeinfo()
    {
        val body = """
            {"configNodeInfo":{"codeVersion":"GreatVoyage-Nile-v4.8.2.1","p2pVersion":"2025102201"}}
        """.trimIndent()
        assertEquals("GreatVoyage-Nile-v4.8.2.1", TronWalletRpc.parseCodeVersion(body))
    }

    @Test
    fun parse_code_version_rejects_html()
    {
        assertNull(TronWalletRpc.parseCodeVersion("<html><head><meta http-equiv=\"Content"))
        assertTrue(TronWalletRpc.looksLikeHtml("<html>…"))
    }
}
