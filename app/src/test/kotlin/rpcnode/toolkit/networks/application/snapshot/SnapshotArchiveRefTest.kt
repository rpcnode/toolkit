package rpcnode.toolkit.networks.application.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SnapshotArchiveRefTest
{
    @Test
    fun extracts_backup_version_and_filename()
    {
        val url = "https://mirror.example/backup20260808/FullNode_output-directory.tgz"
        assertEquals("backup20260808", SnapshotArchiveRef.versionFromUrl(url))
        assertEquals("FullNode_output-directory.tgz", SnapshotArchiveRef.filenameFromUrl(url))
    }

    @Test
    fun junk_url_is_null()
    {
        assertNull(SnapshotArchiveRef.versionFromUrl("not a url"))
        assertNull(SnapshotArchiveRef.filenameFromUrl(""))
    }
}
