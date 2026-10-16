package rpcnode.toolkit.chains.polygon.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotPaths
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef

/**
 * Runs the whole restore script (prelude + Polygon body) on stand-ins for curl / lz4 / df / stat / systemctl with real tar archives
 * of the layouts PublicNode publishes (`data/application.db/…` for Heimdall, `bor/chaindata/…` for Bor), and checks the disk.
 */
class PolygonSnapshotRestoreTest
{
    private val bash: String = listOf("/opt/homebrew/bin/bash", "/usr/local/bin/bash", "/bin/bash", "/usr/bin/bash")
        .firstOrNull { Files.isExecutable(Path.of(it)) } ?: "bash"

    private class Sandbox(val root: Path)
    {
        val borDir: Path = Files.createDirectories(root.resolve("node/bor"))
        val heimdall: Path = Files.createDirectories(root.resolve("node/heimdall"))
        val stubs: Path = Files.createDirectories(root.resolve("stubs"))
        val fix: Path = Files.createDirectories(root.resolve("fixtures"))
        val calls: Path = root.resolve("calls.log")
        fun calls(): List<String> = if (Files.exists(calls)) Files.readAllLines(calls) else emptyList()
    }

    private fun stub(sb: Sandbox, name: String, body: String)
    {
        val f = sb.stubs.resolve(name)
        Files.writeString(f, "#!/bin/bash\n$body\n")
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun tarOf(sb: Sandbox, name: String, vararg files: String): Path
    {
        val src = Files.createDirectories(sb.fix.resolve("src-$name"))
        for (f in files)
        {
            Files.createDirectories(src.resolve(f).parent)
            Files.writeString(src.resolve(f), "payload of $f")
        }
        val out = sb.fix.resolve("$name.tar")
        val p = ProcessBuilder("tar", "-cf", out.toString(), "-C", src.toString(), ".").redirectErrorStream(true).start()
        check(p.waitFor() == 0) { p.inputStream.bufferedReader().readText() }
        return out
    }

    private val chainFile = "bor/chaindata/ancient/chain/bodies.0000.cdat"

    private fun sandbox(
        heimdallFiles: Array<String>,
        borBaseFiles: Array<String>,
        borPartFiles: Array<String>,
        page: (String) -> String = { it },
    ): Sandbox
    {
        val sb = Sandbox(Files.createTempDirectory("polygon-restore"))
        val pageFile = sb.fix.resolve("page.html")
        Files.writeString(
            pageFile,
            page(checkNotNull(javaClass.classLoader.getResourceAsStream("polygon/publicnode-snapshots-links.html")).readBytes().toString(Charsets.UTF_8)),
        )
        val heimdallTar = tarOf(sb, "heimdall", *heimdallFiles)
        val borBaseTar = tarOf(sb, "borbase", *borBaseFiles)
        val borPartTar = tarOf(sb, "borpart", *borPartFiles)
        val c = sb.calls.toString()
        // one-byte range probes answer Content-Range; the page and the two archives are copied from the fixtures
        stub(
            sb, "curl",
            """
            echo "curl ${'$'}*" >> '$c'
            out=""; url=""; range=0
            while [ ${'$'}# -gt 0 ]; do
              case "${'$'}1" in -o) out="${'$'}2"; shift ;; -r) range=1; shift ;; -D) shift ;; -*) ;; *) url="${'$'}1" ;; esac
              shift
            done
            if [ ${'$'}range = 1 ]; then printf 'HTTP/2 206\r\ncontent-range: bytes 0-0/52428800\r\n\r\n'; exit 0; fi
            case "${'$'}url" in
              */snapshots) cp '$pageFile' "${'$'}out" ;;
              *heimdall*) cp '$heimdallTar' "${'$'}out" ;;
              *bor-base*) cp '$borBaseTar' "${'$'}out" ;;
              *bor-part*) cp '$borPartTar' "${'$'}out" ;;
              *) exit 22 ;;
            esac
            """.trimIndent(),
        )
        stub(sb, "lz4", "[ \"\$1\" = -dc ] && exec cat \"\$2\"; exit 2")
        stub(sb, "stat", "wc -c < \"\$3\" | tr -d ' '")
        stub(sb, "df", "echo Avail; echo 99999999999999")
        stub(sb, "sleep", "exit 0")
        stub(sb, "systemctl", "echo \"systemctl \$*\" >> '$c'")
        return sb
    }

    private fun run(sb: Sandbox): Path
    {
        val paths = EvmSnapshotPaths(
            root = sb.borDir,
            bins = emptyMap(),
            marker = sb.borDir.resolve(".toolkit/evm-snapshot.done"),
            state = sb.borDir.resolve(".toolkit/evm-snapshot-state.json"),
            logFile = sb.borDir.resolve("logs/evm-snapshot.log"),
            script = sb.borDir.resolve(".toolkit/evm-snapshot.sh"),
        )
        val script = PolygonSnapshot.snapshotScript(
            EvmSnapshotRef("polygon", "amoy", "full", mapOf("dir_bor" to sb.borDir.toString(), "dir_heimdall" to sb.heimdall.toString())),
            paths,
        )
        Files.createDirectories(sb.borDir.resolve(".toolkit"))
        val file = sb.borDir.resolve(".toolkit/evm-snapshot.sh")
        Files.writeString(file, script)
        val pb = ProcessBuilder(bash, file.toString()).redirectErrorStream(true)
        pb.environment()["PATH"] = sb.stubs.toString() + ":" + System.getenv("PATH")
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (!Files.exists(paths.state)) println("no state file; output:\n$out")
        return paths.state
    }

    @Test
    fun a_good_restore_replaces_the_data_dirs_keeps_the_config_and_finishes()
    {
        val sb = sandbox(
            arrayOf("data/application.db/000001.ldb", "data/priv_validator_state.json"),
            arrayOf(chainFile),
            arrayOf("bor/chaindata/000001.sst", "bor/chaindata/CURRENT"),
        )
        // what a node that was started once leaves behind
        Files.createDirectories(sb.heimdall.resolve("config")); Files.writeString(sb.heimdall.resolve("config/keep.txt"), "config and keys")
        Files.createDirectories(sb.heimdall.resolve("data")); Files.writeString(sb.heimdall.resolve("data/stale"), "genesis state")
        val datadirData = sb.borDir.resolve("datadir/data")
        Files.createDirectories(datadirData.resolve("bor/chaindata")); Files.writeString(datadirData.resolve("bor/chaindata/stale"), "block 0")
        Files.createDirectories(datadirData.resolve("bor/triedb")); Files.writeString(datadirData.resolve("bor/triedb/merkle.journal"), "genesis journal")
        Files.createDirectories(datadirData.resolve("bor/nodes")); Files.writeString(datadirData.resolve("bor/nodes/keep"), "p2p identity")

        val state = run(sb)

        assertTrue(Files.readString(state).contains("\"phase\":\"done\""), Files.readString(state))
        assertTrue(Files.exists(sb.borDir.resolve(".toolkit/evm-snapshot.done")), "marker written")
        assertTrue(Files.exists(sb.heimdall.resolve("data/application.db/000001.ldb")))
        assertFalse(Files.exists(sb.heimdall.resolve("data/stale")), "the old Heimdall data is replaced")
        assertEquals("config and keys", Files.readString(sb.heimdall.resolve("config/keep.txt")), "config/ is never touched")
        assertTrue(Files.exists(datadirData.resolve("bor/chaindata/000001.sst")), "part (state and recent blocks) is unpacked")
        assertTrue(Files.exists(datadirData.resolve(chainFile)), "base (the chain history) is unpacked into the same tree")
        assertFalse(Files.exists(datadirData.resolve("bor/chaindata/stale")), "old chaindata is gone")
        assertFalse(Files.exists(datadirData.resolve("bor/triedb/merkle.journal")), "a genesis journal must not meet the new chaindata")
        assertTrue(Files.exists(datadirData.resolve("bor/nodes/keep")), "the p2p identity stays")
        assertFalse(Files.exists(sb.borDir.resolve("snapshots")), "the staging directory is removed")
        val calls = sb.calls()
        assertTrue(calls.contains("systemctl stop rpcnode-polygon-amoy.service") && calls.contains("systemctl stop rpcnode-polygon-heimdall-amoy.service"), calls.toString())
        val downloads = calls.filter { it.startsWith("curl") && it.contains("-C -") }
        assertEquals(3, downloads.size, "one resumable download per archive: $calls")
        assertTrue(downloads[0].contains("polygon-amoy-heimdall-pruned-49342317-49342327.tar.lz4"), "Heimdall first, the newest link")
        assertTrue(downloads[1].contains("polygon-amoy-bor-base-0-23132104.tar.lz4"), "Bor history first")
        assertTrue(downloads[2].contains("polygon-amoy-bor-part-23132105-50285366.tar.lz4"), "then the rest and the state")
    }

    @Test
    fun an_archive_with_an_unexpected_layout_stops_with_an_error_and_writes_no_marker()
    {
        val sb = sandbox(arrayOf("something-else/file"), arrayOf(chainFile), arrayOf("bor/chaindata/000001.sst"))
        val state = run(sb)
        val text = Files.readString(state)
        assertTrue(text.contains("\"phase\":\"error\"") && text.contains("data/application.db"), text)
        assertFalse(Files.exists(sb.borDir.resolve(".toolkit/evm-snapshot.done")), "no marker: the node must not start on a half restore")
        assertFalse(Files.exists(sb.borDir.resolve("datadir/data/bor/chaindata/000001.sst")), "Bor was not touched after Heimdall failed")
    }

    @Test
    fun a_bor_archive_with_an_unexpected_layout_is_reported_after_heimdall_succeeded()
    {
        val sb = sandbox(arrayOf("data/application.db/000001.ldb"), arrayOf("chaindata/000001.sst"), arrayOf("chaindata/000002.sst"))
        val state = run(sb)
        val text = Files.readString(state)
        assertTrue(text.contains("\"phase\":\"error\"") && text.contains("bor/chaindata"), text)
        assertFalse(Files.exists(sb.borDir.resolve(".toolkit/evm-snapshot.done")))
        assertTrue(Files.exists(sb.heimdall.resolve("data/application.db/000001.ldb")), "Heimdall is already restored")
    }

    @Test
    fun a_bor_snapshot_without_chain_history_is_refused_before_the_node_can_start_on_it()
    {
        // what the PublicNode "pruned" archive looks like: the freezer files exist but are empty, Bor would stop with "ancient chain segments already extracted"
        val sb = sandbox(arrayOf("data/application.db/000001.ldb"), arrayOf("bor/chaindata/CURRENT"), arrayOf("bor/chaindata/000001.sst"))
        val text = Files.readString(run(sb))
        assertTrue(text.contains("\"phase\":\"error\"") && text.contains("chain history"), text)
        assertFalse(Files.exists(sb.borDir.resolve(".toolkit/evm-snapshot.done")), "no marker: the node must not start on a half restore")
    }

    @Test
    fun a_part_that_does_not_continue_the_base_is_refused_before_anything_is_downloaded()
    {
        // the two files are republished at different times: base ends at 23132104, this part would start at 23132200
        val sb = sandbox(
            arrayOf("data/application.db/000001.ldb"), arrayOf(chainFile), arrayOf("bor/chaindata/000001.sst"),
            page = { it.replace("polygon-amoy-bor-part-23132105-50285366", "polygon-amoy-bor-part-23132200-50285366") },
        )
        val text = Files.readString(run(sb))
        assertTrue(text.contains("\"phase\":\"error\"") && text.contains("do not line up"), text)
        assertEquals(0, sb.calls().count { it.startsWith("curl") && it.contains("-C -") }, "nothing is downloaded for a hole in the history")
        assertFalse(Files.exists(sb.heimdall.resolve("data")), "the data directories were not touched")
    }

    @Test
    fun a_finished_restore_is_not_repeated()
    {
        val sb = sandbox(arrayOf("data/application.db/000001.ldb"), arrayOf(chainFile), arrayOf("bor/chaindata/000001.sst"))
        run(sb)
        val before = sb.calls().size
        val state = run(sb)
        assertTrue(Files.readString(state).contains("\"phase\":\"done\""))
        assertEquals(0, sb.calls().drop(before).count { it.startsWith("curl") }, "the marker stops a second download")
    }
}
