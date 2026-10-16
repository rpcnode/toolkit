package rpcnode.toolkit.agent.infrastructure.http

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SnapshotStreamTarArgsTest
{
    @Test
    fun compression_follows_the_url_suffix()
    {
        assertEquals(listOf("-I", "lz4", "-xf", "-"), SnapshotStreamExtract.streamTarArgs("https://x/a_1.tar.lz4"))
        assertEquals(listOf("-I", "lz4", "-xf", "-"), SnapshotStreamExtract.streamTarArgs("https://x/a_1.tar.lz4?sig=1"))
        assertEquals(listOf("-I", "zstd", "-xf", "-"), SnapshotStreamExtract.streamTarArgs("https://x/a.tar.zst"))
        assertEquals(listOf("-xf", "-"), SnapshotStreamExtract.streamTarArgs("https://x/a.tar"))
        assertEquals(listOf("-xzf", "-"), SnapshotStreamExtract.streamTarArgs("https://x/a.tgz"))
        assertEquals(listOf("-xzf", "-"), SnapshotStreamExtract.streamTarArgs("https://x/a.tar.gz"))
    }

    @Test
    fun juno_mirror_latest_url_is_a_zstd_stream_although_it_has_no_suffix()
    {
        val zstd = listOf("-I", "zstd", "-xf", "-")
        assertEquals(zstd, SnapshotStreamExtract.streamTarArgs("https://juno-snapshots.nethermind.io/files/mainnet/latest"))
        assertEquals(zstd, SnapshotStreamExtract.streamTarArgs("https://juno-snapshots.nethermind.io/files/sepolia-pruned/latest"))
        // any other host keeps the suffix rule
        assertEquals(listOf("-xzf", "-"), SnapshotStreamExtract.streamTarArgs("https://example.com/files/mainnet/latest"))
    }

    @Test
    fun a_stale_pebble_file_in_the_db_directory_is_cleared_before_the_next_extract_but_client_zips_stay()
    {
        val dir = Files.createTempDirectory("juno-clear-")
        for (db in listOf("307889.sst", "000012.log", "MANIFEST-000007", "OPTIONS-000009", "CURRENT", "LOCK", "marker.format-version.000001.000013"))
        {
            assertFalse(SnapshotStreamExtract.shouldPreserveAlongsideSnapshot(Files.writeString(dir.resolve(db), "x")), db)
        }
        assertTrue(SnapshotStreamExtract.shouldPreserveAlongsideSnapshot(Files.writeString(dir.resolve("juno-x86_64-linux.zip"), "x")))
        assertTrue(SnapshotStreamExtract.shouldPreserveAlongsideSnapshot(Files.createDirectories(dir.resolve(".toolkit"))))
    }
}
