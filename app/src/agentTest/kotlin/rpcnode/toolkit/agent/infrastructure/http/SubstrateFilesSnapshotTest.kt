package rpcnode.toolkit.agent.infrastructure.http

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateDb
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateSnapshotResolver

class SubstrateFilesSnapshotTest
{
    private val files = mapOf(
        "full/index_00_21" to ByteArray(300_000) { (it % 251).toByte() },
        "full/table_09_08" to ByteArray(70_000) { (it % 13).toByte() },
        "full/metadata" to ByteArray(10) { 7 },
    )

    private fun server(listing: String = files.keys.joinToString("\n") + "\n", missing: Set<String> = emptySet()): HttpServer
    {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/snap/20261013-1") { ex ->
            val rel = ex.requestURI.path.removePrefix("/snap/20261013-1/")
            val body = if (rel == "files.txt") listing.toByteArray() else files[rel]
            if (body == null || rel in missing)
            {
                ex.sendResponseHeaders(404, -1)
            }
            else if (ex.requestMethod == "HEAD")
            {
                ex.responseHeaders.add("Content-Length", body.size.toString())
                ex.sendResponseHeaders(200, -1)
            }
            else
            {
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            ex.close()
        }
        s.start()
        return s
    }

    private fun ref(port: Int) = SubstrateSnapshotResolver.Ref("http://127.0.0.1:$port/snap/20261013-1", SubstrateDb.PARITYDB, "polkadot", "polkadot", "mainnet")

    @Test
    fun restores_every_manifest_file_into_the_chain_database_dir()
    {
        val s = server()
        try
        {
            val dest = createTempDirectory("sub")
            // a database of an older snapshot must not survive
            Files.createDirectories(dest.resolve("chains/polkadot/paritydb/full"))
            Files.writeString(dest.resolve("chains/polkadot/paritydb/full/stale"), "x")
            val seen = mutableListOf<SubstrateFilesSnapshot.Progress>()
            SubstrateFilesSnapshot(parallel = 3).run(ref(s.address.port), dest, "", { false }, {}, { seen += it })
            for ((rel, bytes) in files)
            {
                assertTrue(bytes.contentEquals(Files.readAllBytes(dest.resolve("chains/polkadot/paritydb/$rel"))), rel)
            }
            assertTrue(Files.notExists(dest.resolve("chains/polkadot/paritydb/full/stale")))
            assertTrue(Files.isRegularFile(dest.resolve(".snapshot-ready")))
            assertTrue(seen.isNotEmpty() && seen.last().filesDone == 3 && seen.last().pct > 99.0, seen.lastOrNull().toString())
            assertTrue(Files.walk(dest).use { w -> w.noneMatch { it.toString().endsWith(".part") } })
            // a second run is a no-op
            val logs = mutableListOf<String>()
            SubstrateFilesSnapshot(parallel = 3).run(ref(s.address.port), dest, "", { false }, { logs += it }, {})
            assertTrue(logs.any { it.contains("already restored") })
        }
        finally
        {
            s.stop(0)
        }
    }

    @Test
    fun resumes_a_partial_restore_and_skips_complete_files()
    {
        val s = server()
        try
        {
            val dest = createTempDirectory("sub2")
            val db = dest.resolve("chains/polkadot/paritydb")
            Files.createDirectories(db.resolve("full"))
            Files.createDirectories(dest.resolve(".toolkit"))
            Files.writeString(dest.resolve(".toolkit/${SubstrateFilesSnapshot.STATE_FILE}"), "http://127.0.0.1:${s.address.port}/snap/20261013-1\n")
            Files.write(db.resolve("full/index_00_21"), files["full/index_00_21"]!!)
            val marker = Files.getLastModifiedTime(db.resolve("full/index_00_21"))
            Files.write(db.resolve("full/table_09_08.part"), ByteArray(5))
            SubstrateFilesSnapshot(parallel = 2).run(ref(s.address.port), dest, "", { false }, {}, {})
            assertEquals(marker, Files.getLastModifiedTime(db.resolve("full/index_00_21")), "complete file untouched")
            assertTrue(files["full/table_09_08"]!!.contentEquals(Files.readAllBytes(db.resolve("full/table_09_08"))))
            assertTrue(Files.notExists(db.resolve("full/table_09_08.part")))
        }
        finally
        {
            s.stop(0)
        }
    }

    @Test
    fun a_missing_file_fails_the_restore_and_no_ready_marker_is_written()
    {
        val s = server(missing = setOf("full/metadata"))
        try
        {
            val dest = createTempDirectory("sub3")
            assertFailsWith<IllegalStateException> {
                SubstrateFilesSnapshot(parallel = 2).run(ref(s.address.port), dest, "", { false }, {}, {})
            }
            assertTrue(Files.notExists(dest.resolve(".snapshot-ready")))
        }
        finally
        {
            s.stop(0)
        }
    }

    @Test
    fun the_manifest_cannot_leave_the_database_directory()
    {
        val snap = SubstrateFilesSnapshot()
        assertEquals(listOf("full/a", "full/b"), snap.parseManifest("full/a\n\nfull/b\nfull/a\n"))
        assertFailsWith<IllegalStateException> { snap.parseManifest("../../etc/passwd") }
        assertFailsWith<IllegalStateException> { snap.parseManifest("/etc/passwd") }
        assertFailsWith<IllegalStateException> { snap.parseManifest("full/a b") }
    }
}
