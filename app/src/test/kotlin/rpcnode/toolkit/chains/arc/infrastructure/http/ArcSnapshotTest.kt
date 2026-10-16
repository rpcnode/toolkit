package rpcnode.toolkit.chains.arc.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.arc.infrastructure.ArcLayout

class ArcSnapshotTest
{
    @Test
    fun the_resolver_gives_a_sentinel_per_env_and_node_type_that_parses_back() = runTest {
        val archive = ArcSnapshotResolver().resolve(EnvId.TESTNET, "archive")!!
        assertEquals("arc-snapshots://testnet/archive", archive.url)
        assertFalse(archive.streamUnpack)
        assertEquals("arc-snapshots://mainnet/full", ArcSnapshotResolver().resolve(EnvId.MAINNET, "")!!.url)

        assertEquals(ArcSnapshotResolver.Ref("testnet", "archive"), ArcSnapshotResolver.parse(archive.url))
        assertTrue(ArcSnapshotResolver.isOfficialUrl(" ARC-SNAPSHOTS://x "))
        assertNull(ArcSnapshotResolver.parse("https://snapshots.arc.network/x.tar"))
    }

    @Test
    fun databases_live_in_their_own_subdirectories_and_the_marker_is_per_node_type()
    {
        val root = Path.of("/data/arc")
        assertEquals(Path.of("/data/arc/execution"), ArcLayout.execution(root))
        assertEquals(Path.of("/data/arc/consensus"), ArcLayout.consensus(root))
        assertEquals(Path.of("/data/arc/.toolkit/arc-snapshot-archive.done"), ArcLayout.snapshotMarker(root, "archive"))
        assertEquals(Path.of("/data/arc/.toolkit/arc-snapshot-full.done"), ArcLayout.snapshotMarker(root, "whatever"))
    }

    @Test
    fun the_script_restores_both_layers_and_writes_the_marker_the_run_script_checks()
    {
        val dir = Files.createTempDirectory("arc-snap")
        try
        {
            val runner = ArcSnapshotRunner()
            val paths = runner.layout(dir, "archive")
            val script = runner.writeScript(paths, "testnet", "archive", dir.resolve("bin/arc-snapshots"))
            val body = Files.readString(script)
            assertFalse(body.contains("{{"))
            assertTrue(body.contains("CHAIN='arc-testnet'"))
            assertTrue(body.contains("EXEC='${dir.toAbsolutePath().normalize()}/execution'"))
            assertTrue(body.contains("CONS='${dir.toAbsolutePath().normalize()}/consensus'"))
            assertTrue(body.contains("--el-profile archive"))
            assertTrue(body.contains("--execution-path \"\$EXEC\" --consensus-path \"\$CONS\""))
            assertTrue(body.contains("MARKER='${dir.toAbsolutePath().normalize()}/.toolkit/arc-snapshot-archive.done'"))
            val p = ProcessBuilder("bash", "-n", script.toString()).redirectErrorStream(true).start()
            assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
        }
        finally
        {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun progress_prefers_the_tools_percentage_and_falls_back_to_the_size_on_disk()
    {
        val dir = Files.createTempDirectory("arc-prog")
        try
        {
            val runner = ArcSnapshotRunner()
            val paths = runner.layout(dir, "full")
            Files.createDirectories(paths.execution)
            Files.write(paths.execution.resolve("blob"), ByteArray(3 * 1024 * 1024))
            Files.createDirectories(paths.logFile.parent)
            Files.createDirectories(paths.state.parent)
            Files.writeString(paths.state, """{"phase":"download","pct":0,"detail":"Arc snapshot · resolving…"}""")

            val noPct = runner.readLiveProgress(paths)!!
            assertNull(noPct.pct)
            assertTrue(noPct.detail.contains("3 MiB on disk"), noPct.detail)

            Files.writeString(paths.logFile, "downloading execution 12%\ndownloading execution 47.5% done\n")
            assertEquals(47.5, runner.readLiveProgress(paths)!!.pct)

            Files.writeString(paths.state, """{"phase":"error","pct":0,"detail":"x","error":"boom"}""")
            assertEquals("boom", runner.readLiveProgress(paths)!!.error)
        }
        finally
        {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun a_failed_run_reports_the_script_detail_and_the_tool_output()
    {
        val dir = Files.createTempDirectory("arc-fail")
        try
        {
            val runner = ArcSnapshotRunner()
            val paths = runner.layout(dir, "full")
            Files.createDirectories(paths.state.parent)
            Files.createDirectories(paths.logFile.parent)
            Files.writeString(paths.state, """{"phase":"error","pct":0,"detail":"arc-snapshots download failed rc=1","updated_at":"x"}""")
            Files.writeString(paths.logFile, "SNAPSHOT_DIAG begin\nresolving\n 40%\rError: not enough free space\nSNAPSHOT_DIAG download_done rc=1\n")

            assertEquals("arc-snapshots download failed rc=1", runner.failureMessage(paths.state))
            assertEquals(listOf("resolving", "Error: not enough free space"), runner.failureTail(paths.logFile))
        }
        finally
        {
            dir.toFile().deleteRecursively()
        }
    }
}
