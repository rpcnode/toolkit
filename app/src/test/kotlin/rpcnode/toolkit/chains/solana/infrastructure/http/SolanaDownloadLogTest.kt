package rpcnode.toolkit.chains.solana.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SolanaDownloadLogTest
{
    @Test
    fun parses_solana_file_download_line()
    {
        val text = """
            [2026-09-02T14:45:48.228Z INFO  solana_file_download] downloaded 8668566 bytes 0.2% 61837.8 bytes/s
            [2026-09-02T14:45:54.238Z INFO  solana_file_download] downloaded 9017562 bytes 12.5% 58070.4 bytes/s
        """.trimIndent()
        val got = assertNotNull(SolanaDownloadLog.parse(text))
        assertEquals(12.5, got.pct)
        assertEquals(9_017_562L, got.bytes)
        assertEquals(58070.4, got.bytesPerSec)
    }

    @Test
    fun parses_snapshot_download_phrase()
    {
        val got = assertNotNull(SolanaDownloadLog.parse("snapshot download 28.0% remaining"))
        assertEquals(28.0, got.pct)
    }

    @Test
    fun empty_when_no_match()
    {
        assertNull(SolanaDownloadLog.parse("metrics disabled: environment variable not found"))
    }
}
