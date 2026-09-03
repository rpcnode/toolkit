package rpcnode.toolkit.settings.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SnapshotCdnOriginTest
{
    @Test
    fun parse_http_https_and_rejects_junk()
    {
        assertIs<SnapshotCdnOrigin.Parse.Empty>(SnapshotCdnOrigin.parse(""))
        assertIs<SnapshotCdnOrigin.Parse.Empty>(SnapshotCdnOrigin.parse("  "))
        assertIs<SnapshotCdnOrigin.Parse.Invalid>(SnapshotCdnOrigin.parse("ftp://x"))
        assertIs<SnapshotCdnOrigin.Parse.Invalid>(SnapshotCdnOrigin.parse("not-a-url"))

        val parsed = assertIs<SnapshotCdnOrigin.Parse.Ok>(
            SnapshotCdnOrigin.parse("http://127.0.0.1:8095/"),
        )
        assertEquals("http://127.0.0.1:8095", parsed.origin.value)
    }

    @Test
    fun does_not_remap_8095_to_panel()
    {
        val parsed = assertIs<SnapshotCdnOrigin.Parse.Ok>(
            SnapshotCdnOrigin.parse("http://localhost:8095"),
        )
        assertEquals("http://localhost:8095", parsed.origin.value)
    }
}
