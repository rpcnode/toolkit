package rpcnode.toolkit.chains.cardano.infrastructure.proc

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoHostBinaries
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoIndexerBodies
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoIndexerMode
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoPortTable

class CardanoIndexerSetupTest
{
    private val root: Path = Files.createTempDirectory("cardano-node-dir")
    private val idx: Path = Files.createTempDirectory("cardano-idx-dir")
    private val ports = CardanoPortTable.forEnv("preprod")
    private val socket = root.resolve("node.socket")

    @AfterTest
    fun cleanup()
    {
        root.toFile().deleteRecursively()
        idx.toFile().deleteRecursively()
    }

    private fun writeOgmiosZip()
    {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("bin/ogmios")); zip.write("#!/bin/sh\necho ogmios\n".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("share/bash-completion/completions/ogmios")); zip.write("x".toByteArray()); zip.closeEntry()
        }
        Files.write(root.resolve("ogmios-linux-x86_64.zip"), bytes.toByteArray())
    }

    private fun writeDbSyncTarball()
    {
        val stage = Files.createTempDirectory("dbsync-stage")
        try
        {
            Files.createDirectories(stage.resolve("bin")); Files.createDirectories(stage.resolve("schema"))
            Files.writeString(stage.resolve("bin/cardano-db-sync"), "#!/bin/sh\necho dbsync\n")
            Files.writeString(stage.resolve("bin/cardano-db-tool"), "#!/bin/sh\n")
            Files.writeString(stage.resolve("schema/migration-1-0000-20190730.sql"), "select 1;\n")
            val p = ProcessBuilder("tar", "-czf", root.resolve("cardano-db-sync-linux-x86_64.tar.gz").toString(), "-C", stage.toString(), "./bin", "./schema")
                .redirectErrorStream(true).start()
            p.inputStream.readBytes()
            assertEquals(0, p.waitFor())
        }
        finally
        {
            stage.toFile().deleteRecursively()
        }
    }

    private fun nodeConfig()
    {
        Files.createDirectories(root.resolve("config"))
        Files.writeString(root.resolve("config/config.json"), "{}")
    }

    private fun prepare(mode: CardanoIndexerMode) =
        CardanoIndexerSetup.prepare(root, "preprod", mode, idx, ports, socket)

    @Test
    fun none_writes_only_the_state_file()
    {
        nodeConfig()
        val r = prepare(CardanoIndexerMode.NONE) as CardanoIndexerSetup.Result.Ok
        assertTrue(r.companions.isEmpty())
        val state = Json.parseToJsonElement(Files.readString(root.resolve(CardanoIndexerBodies.STATE_FILE))).jsonObject
        assertEquals("none", state.getValue("mode").jsonPrimitive.content)
        assertFalse(Files.exists(root.resolve("bin/ogmios")))
    }

    @Test
    fun ogmios_is_unpacked_and_gets_a_unit_on_the_catalog_port()
    {
        nodeConfig(); writeOgmiosZip()
        val r = prepare(CardanoIndexerMode.OGMIOS) as CardanoIndexerSetup.Result.Ok
        assertEquals(listOf("rpcnode-cardano-preprod-ogmios.service"), r.companions.map { it.first })
        assertTrue(Files.isExecutable(root.resolve("bin/ogmios")))
        val unit = r.companions.single().second
        assertTrue(unit.contains("ExecStart=${root.toAbsolutePath()}/bin/ogmios --node-socket $socket --node-config ${root}/config/config.json --host 127.0.0.1 --port 41220"), unit)
        assertTrue(unit.contains("Environment=CARDANO_NODE_SOCKET_PATH=$socket"))
        assertFalse(Files.exists(root.resolve("bin/cardano-db-sync")))
    }

    @Test
    fun db_sync_is_unpacked_with_its_schema_scripts_hook_and_units_in_start_order()
    {
        nodeConfig(); writeOgmiosZip(); writeDbSyncTarball()
        val r = prepare(CardanoIndexerMode.BOTH) as CardanoIndexerSetup.Result.Ok
        assertEquals(
            listOf("rpcnode-cardano-preprod-dbsync-prepare.service", "rpcnode-cardano-preprod-dbsync.service", "rpcnode-cardano-preprod-ogmios.service"),
            r.companions.map { it.first },
        )
        assertEquals(CardanoIndexerSetup.allUnits("preprod").toSet(), r.companions.map { it.first }.toSet())
        assertTrue(Files.isExecutable(root.resolve("bin/cardano-db-sync")))
        assertTrue(Files.isRegularFile(root.resolve("schema/migration-1-0000-20190730.sql")))
        assertTrue(Files.isDirectory(idx.resolve("ledger-state")))
        // generated db-sync config next to the node config
        val cfg = Json.parseToJsonElement(Files.readString(root.resolve("config/db-sync-config.json"))).jsonObject
        assertEquals("preprod", cfg.getValue("NetworkName").jsonPrimitive.content)
        assertEquals("41227", cfg.getValue("PrometheusPort").jsonPrimitive.content)
        // scripts: executable, private state dir, hook for the node removal
        for (script in listOf(CardanoIndexerBodies.PREPARE_SCRIPT, CardanoIndexerBodies.RUN_SCRIPT, CardanoIndexerBodies.REMOVE_HOOK))
        {
            assertTrue(Files.getPosixFilePermissions(root.resolve(script)).contains(PosixFilePermission.OWNER_EXECUTE), script)
        }
        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(CardanoIndexerBodies.PW_DIR))))
        val prepareScript = Files.readString(root.resolve(CardanoIndexerBodies.PREPARE_SCRIPT))
        assertTrue(prepareScript.contains("IDX='${idx.toAbsolutePath()}'") || prepareScript.contains("IDX='$idx'"), prepareScript.lines().first { it.startsWith("IDX=") })
        val run = Files.readString(root.resolve(CardanoIndexerBodies.RUN_SCRIPT))
        assertTrue(run.contains("'--state-dir' '${idx.resolve("ledger-state")}'"), run)
        assertTrue(run.contains("'--schema-dir' '$root/schema'"), run)
    }

    @Test
    fun a_new_release_replaces_the_schema_directory()
    {
        nodeConfig(); writeDbSyncTarball()
        Files.createDirectories(root.resolve("schema"))
        Files.writeString(root.resolve("schema/migration-9-9999-stale.sql"), "stale")
        assertTrue(prepare(CardanoIndexerMode.DBSYNC) is CardanoIndexerSetup.Result.Ok)
        assertFalse(Files.exists(root.resolve("schema/migration-9-9999-stale.sql")))
        assertTrue(Files.isRegularFile(root.resolve("schema/migration-1-0000-20190730.sql")))
    }

    @Test
    fun a_missing_archive_fails_with_a_reason_instead_of_starting_half_an_indexer()
    {
        nodeConfig()
        val og = prepare(CardanoIndexerMode.OGMIOS) as CardanoIndexerSetup.Result.Failed
        assertTrue(og.detail.contains("Ogmios archive missing"), og.detail)
        val db = prepare(CardanoIndexerMode.DBSYNC) as CardanoIndexerSetup.Result.Failed
        assertTrue(db.detail.contains("x86_64"), db.detail)
        assertTrue(CardanoHostBinaries.ensureDbSync(root) is CardanoHostBinaries.Unpacked.Failed)
    }
}
