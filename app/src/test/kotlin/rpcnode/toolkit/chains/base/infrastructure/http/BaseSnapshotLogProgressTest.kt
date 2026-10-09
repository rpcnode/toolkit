package rpcnode.toolkit.chains.base.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BaseSnapshotLogProgressTest
{
    @Test
    fun parses_byte_ratio_and_bands_download_pct()
    {
        val log = """
            SNAPSHOT_DIAG begin pid=1 env=sepolia
            SNAPSHOT_DIAG mode=download
            downloading archives 12/100 (45.5%) 120.0 GiB / 264.0 GiB eta 1h20m
        """.trimIndent()
        val p = assertNotNull(BaseSnapshotLogProgress.parse(log, "full"))
        assertEquals(BaseSnapshotLogProgress.PHASE_DOWNLOAD, p.phase)
        assertNotNull(p.pct)
        assertTrue(p.pct!! in 40.0..42.0, "banded download pct was ${p.pct}")
        assertTrue(p.detail.contains("downloading"))
        assertTrue(p.detail.contains("GiB"))
    }

    @Test
    fun finished_run_is_100()
    {
        val log = """
            SNAPSHOT_DIAG begin
            SNAPSHOT_DIAG DONE base full mode=download
        """.trimIndent()
        val p = assertNotNull(BaseSnapshotLogProgress.parse(log, "archive"))
        assertEquals(100.0, p.pct)
        assertEquals(BaseSnapshotLogProgress.PHASE_DONE, p.phase)
    }
}
