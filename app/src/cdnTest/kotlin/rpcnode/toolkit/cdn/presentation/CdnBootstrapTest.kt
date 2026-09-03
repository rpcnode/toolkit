package rpcnode.toolkit.cdn.presentation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CdnBootstrapTest
{
    @Test
    fun env_file_roundtrip()
    {
        val dir = Files.createTempDirectory("cdn-env")
        val file = dir.resolve("rpcnode-cdn.env")
        CdnEnvFile.write(
            file,
            CdnEnvValues(
                snapshotDir = "/data/cdn",
                pollSec = "120",
                downloadJobs = "2",
                targetsFile = "/data/cdn/targets.json",
            ),
        )
        val parsed = CdnEnvFile.read(file)!!
        assertEquals("/data/cdn", parsed.snapshotDir)
        assertEquals("120", parsed.pollSec)
        assertEquals("2", parsed.downloadJobs)
        assertEquals("/data/cdn/targets.json", parsed.targetsFile)
        assertNull(CdnEnvFile.parse("PANEL_URL=http://x\n").snapshotDir)
    }

    @Test
    fun env_file_roundtrip_public_origin()
    {
        val dir = Files.createTempDirectory("cdn-env-origin")
        val file = dir.resolve("rpcnode-cdn.env")
        CdnEnvFile.write(
            file,
            CdnEnvValues(
                snapshotDir = "/data/cdn",
                pollSec = "60",
                publicOrigin = "http://cdn.example:8095",
            ),
        )
        val parsed = CdnEnvFile.read(file)!!
        assertEquals("http://cdn.example:8095", parsed.publicOrigin)
    }

    @Test
    fun load_uses_env_then_file_and_allows_missing_snapshot_dir()
    {
        val dir = Files.createTempDirectory("cdn-boot")
        val file = dir.resolve("rpcnode-cdn.env")
        val targets = dir.resolve("targets.json")
        val fromEnv = CdnBootstrap.load(
            env = { key ->
                when (key)
                {
                    "CDN_ENV_FILE" -> file.toString()
                    "SNAPSHOT_CDN_DIR" -> "/tmp/cdn-data"
                    "CDN_TARGETS_FILE" -> targets.toString()
                    "CDN_POLL_SEC" -> "90"
                    "CDN_PUBLIC_ORIGIN" -> "http://cdn.example:8095"
                    else -> null
                }
            },
            defaultDir = dir,
        )
        assertEquals("/tmp/cdn-data", fromEnv.snapshotDir)
        assertEquals(targets.toString(), fromEnv.targetsFile)
        assertEquals(90, fromEnv.pollSec)
        assertEquals(file.toString(), fromEnv.envFile)
        assertEquals("http://cdn.example:8095", fromEnv.publicOrigin)
        assertTrue(Files.isRegularFile(file))

        CdnEnvFile.write(
            file,
            CdnEnvValues(
                snapshotDir = "/from-file",
                pollSec = "45",
                targetsFile = dir.resolve("from-file.json").toString(),
            ),
        )
        val fromFile = CdnBootstrap.load(
            env = { key -> if (key == "CDN_ENV_FILE") file.toString() else null },
            defaultDir = dir,
        )
        assertEquals("/from-file", fromFile.snapshotDir)
        assertEquals(45, fromFile.pollSec)

        val empty = CdnBootstrap.load(
            env = { key -> if (key == "CDN_ENV_FILE") dir.resolve("empty.env").toString() else null },
            defaultDir = dir,
        )
        assertNull(empty.snapshotDir)
    }
}
