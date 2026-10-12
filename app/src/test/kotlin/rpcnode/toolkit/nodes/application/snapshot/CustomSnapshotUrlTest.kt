package rpcnode.toolkit.nodes.application.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomSnapshotUrlTest
{
    @Test
    fun accepts_direct_http_and_https_links_and_trims_them()
    {
        assertEquals(
            "http://34.48.6.163/backup20261007/FullNode_output-directory.tgz",
            CustomSnapshotUrl.parse("  http://34.48.6.163/backup20261007/FullNode_output-directory.tgz \n"),
        )
        assertEquals("https://cdn.example.com/a.tar.gz?x=1", CustomSnapshotUrl.parse("https://cdn.example.com/a.tar.gz?x=1"))
    }

    @Test
    fun rejects_everything_that_is_not_an_absolute_http_url()
    {
        for (bad in listOf(null, "", "   ", "ftp://host/a.tgz", "file:///etc/passwd", "/var/a.tgz", "http://", "host/a.tgz", "http://host/a b.tgz"))
        {
            assertNull(CustomSnapshotUrl.parse(bad), "should reject: $bad")
        }
    }

    @Test
    fun recognises_the_custom_source_id()
    {
        assertTrue(CustomSnapshotUrl.isCustom(" Custom "))
        assertFalse(CustomSnapshotUrl.isCustom("cdn"))
        assertFalse(CustomSnapshotUrl.isCustom(null))
    }
}
