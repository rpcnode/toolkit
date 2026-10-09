package rpcnode.toolkit.cdn.presentation

import com.github.ajalt.mordant.rendering.AnsiLevel
import com.github.ajalt.mordant.terminal.Terminal
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.cdn.application.status.MirrorState
import rpcnode.toolkit.cdn.application.sync.SnapshotTarget
import rpcnode.toolkit.cdn.infrastructure.filesystem.CdnMountLister
import rpcnode.toolkit.cdn.infrastructure.filesystem.DiskMirrorStatusReader
import rpcnode.toolkit.cdn.infrastructure.filesystem.FileSnapshotTargetStore
import rpcnode.toolkit.cdn.infrastructure.filesystem.MountPoint

class CdnStatusTableTest
{
    @Test
    fun reader_ready_and_downloading()
    {
        val root = Files.createTempDirectory("cdn-status")
        val ready = root.resolve("snapshots/tron/mainnet/full")
        Files.createDirectories(ready)
        Files.writeString(ready.resolve("VERSION"), "backup20260808\n")
        Files.writeString(
            ready.resolve("manifest.json"),
            """{"network":"tron","env":"mainnet","type":"full","version":"backup20260808","filename":"FullNode_output-directory.tgz","size_bytes":3000000000000,"official_url":"https://x"}""",
        )
        Files.writeString(ready.resolve("FullNode_output-directory.tgz"), "x")

        val dl = root.resolve("snapshots/tron/nile/full")
        Files.createDirectories(dl)
        Files.writeString(dl.resolve("VERSION"), "backup20260801\n")
        Files.writeString(
            dl.resolve("progress.json"),
            """{"network":"tron","env":"nile","type":"full","version":"backup20260901","filename":"FullNode_output-directory.tgz","size_bytes":1000,"official_url":"https://x"}""",
        )
        Files.writeString(dl.resolve("FullNode_output-directory.tgz.tmp"), "abcd")

        val reader = DiskMirrorStatusReader(root)
        val rows = reader.status(
            listOf(
                SnapshotTarget("tron", "mainnet", "full"),
                SnapshotTarget("tron", "nile", "full"),
                SnapshotTarget("tron", "nile", "lite"),
            ),
        )
        assertEquals(MirrorState.READY, rows[0].state)
        assertEquals(0L, rows[0].downloadCount)
        Files.writeString(ready.resolve("downloads.json"), """{"count":42,"updated_at":"2026-09-01T00:00:00Z"}""")
        val rows2 = reader.status(listOf(SnapshotTarget("tron", "mainnet", "full")))
        assertEquals(42L, rows2[0].downloadCount)

        assertEquals(MirrorState.DOWNLOADING, rows[1].state)
        assertEquals(MirrorState.EMPTY, rows[2].state)

        val table = CdnStatusTable.render(rows2)
        assertTrue(table.any { it.contains("DOWNLOADS") })
        assertTrue(table.any { it.contains("42") })
        val tableDl = CdnStatusTable.render(rows)
        assertTrue(tableDl.any { it.contains("downloading") && it.contains("backup20260801 → backup20260901") })
    }

    @Test
    fun menu_can_change_download_dir()
    {
        val dir = Files.createTempDirectory("cdn-menu-dir")
        val envFile = dir.resolve("rpcnode-cdn.env")
        val targets = FileSnapshotTargetStore(dir.resolve("targets.json"))
        val next = dir.resolve("big-disk")
        Files.createDirectories(next)
        val picks = ArrayDeque(listOf(3, 4)) // Change download directory, Quit
        val terminal = Terminal(ansiLevel = AnsiLevel.NONE, interactive = false)
        CdnMenu.run(
            store = targets,
            envFile = envFile,
            snapshotDir = null,
            terminal = terminal,
            pick = { _, _, _ -> picks.removeFirstOrNull() },
            waitContinue = {},
            pickSnapshotDir = { next },
        )
        assertEquals(next.toAbsolutePath().normalize().toString(), CdnEnvFile.read(envFile)?.snapshotDir)
    }

    @Test
    fun ensure_writable_ok_and_err()
    {
        val dir = Files.createTempDirectory("cdn-ensure")
        val ok = CdnSnapshotDirPicker.ensureWritable(dir.resolve("data"))
        assertTrue(ok is CdnSnapshotDirPicker.EnsureResult.Ok)
        assertTrue(Files.isDirectory(dir.resolve("data/snapshots")))

        val file = dir.resolve("not-a-dir")
        Files.writeString(file, "x")
        val asFile = CdnSnapshotDirPicker.ensureWritable(file)
        assertTrue(asFile is CdnSnapshotDirPicker.EnsureResult.Err)
    }

    @Test
    fun df_parse()
    {
        val text = """
            Filesystem     1B-blocks        Used   Available Capacity Mounted on
            /dev/sda1     1000000000   200000000   800000000      20% /
            /dev/sdb1    20000000000  1000000000 19000000000       5% /data
        """.trimIndent()
        val mounts = CdnMountLister.parseDf(text)
        assertEquals(2, mounts.size)
        assertEquals(Path.of("/data"), mounts[1].path)
        assertEquals(19_000_000_000L, mounts[1].freeBytes)
        assertTrue(MountPoint(Path.of("/data"), "/dev/sdb1", 1, 2).label.contains("/data"))
    }
}
