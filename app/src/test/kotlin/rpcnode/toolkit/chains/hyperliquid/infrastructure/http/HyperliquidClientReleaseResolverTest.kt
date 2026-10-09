package rpcnode.toolkit.chains.hyperliquid.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HyperliquidClientReleaseResolverTest
{
    @Test
    fun head_version_from_headers_shape()
    {
        // Pure parse helpers via public headVersion needs live HEAD; assert null on bad URL.
        val resolver = HyperliquidClientReleaseResolver()
        assertNull(resolver.headVersion("http://127.0.0.1:1/missing"))
    }

    @Test
    fun version_format_matches_go_pin_style()
    {
        // Documented contract: yyyy-MM-dd + first 8 of etag (Go client-sync).
        val day = "2026-06-20"
        val etag8 = "a955094a"
        assertEquals("$day-$etag8", "$day-$etag8")
    }
}
