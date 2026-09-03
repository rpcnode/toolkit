package rpcnode.toolkit.cdn.presentation

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CdnSystemInstallTest
{
    @Test
    fun unit_and_env_bodies()
    {
        val unit = CdnSystemInstall.unitFileBody(
            javaBin = "/opt/rpcnode/jdk/bin/java",
            jarFile = java.nio.file.Path.of("/opt/rpcnode/lib/rpcnode-cdn.jar"),
            envFile = java.nio.file.Path.of("/etc/rpcnode/rpcnode-cdn.env"),
            workingDir = java.nio.file.Path.of("/opt/rpcnode"),
        )
        assertTrue(unit.contains("ExecStart=/opt/rpcnode/jdk/bin/java --enable-native-access=ALL-UNNAMED -jar /opt/rpcnode/lib/rpcnode-cdn.jar"))
        assertTrue(!unit.contains("rpcnode-cdn.jar sync"))
        assertTrue(unit.contains("EnvironmentFile=/etc/rpcnode/rpcnode-cdn.env"))
        val env = CdnSystemInstall.envFileBody(
            snapshotDir = java.nio.file.Path.of("/opt/rpcnode/lib"),
            pollSec = 3600,
            targetsFile = java.nio.file.Path.of("/etc/rpcnode/rpcnode-cdn.targets.json"),
        )
        assertEquals(
            """
            SNAPSHOT_CDN_DIR=/opt/rpcnode/lib
            CDN_POLL_SEC=3600
            CDN_TARGETS_FILE=/etc/rpcnode/rpcnode-cdn.targets.json
            """.trimIndent() + "\n",
            env,
        )
    }

    @Test
    fun install_refuses_non_root()
    {
        val code = CdnSystemInstall.install(
            isRoot = { false },
            err = {},
            out = {},
        )
        assertEquals(1, code)
    }

    @Test
    fun install_copies_jar_and_enables_unit()
    {
        val root = Files.createTempDirectory("cdn-install")
        val src = root.resolve("rpcnode-cdn.jar")
        Files.writeString(src, "jar-bytes")
        val dest = root.resolve("opt")
        val etc = root.resolve("etc")
        val unitDir = root.resolve("systemd")
        Files.createDirectories(unitDir)
        val paths = CdnSystemInstall.Paths(
            destDir = dest,
            jarFile = dest.resolve("lib/rpcnode-cdn.jar"),
            envFile = etc.resolve("rpcnode-cdn.env"),
            targetsFile = etc.resolve("rpcnode-cdn.targets.json"),
            unitPath = unitDir.resolve("rpcnode-cdn.service"),
            snapshotDir = null,
            unitName = "rpcnode-cdn.service",
            pollSec = 120,
        )
        val snap = root.resolve("data/cdn")
        val ran = mutableListOf<List<String>>()
        val code = CdnSystemInstall.install(
            paths = paths,
            selfJar = src,
            javaBin = "/usr/bin/java",
            run = { cmd -> ran += cmd; 0 },
            out = {},
            err = {},
            isRoot = { true },
            chooseSnapshotDir = { snap },
        )
        assertEquals(0, code)
        assertTrue(Files.isRegularFile(paths.jarFile))
        assertEquals("jar-bytes", Files.readString(paths.jarFile))
        assertTrue(Files.readString(paths.envFile).contains("SNAPSHOT_CDN_DIR=$snap"))
        assertTrue(Files.readString(paths.envFile).contains("CDN_POLL_SEC=120"))
        assertTrue(Files.isDirectory(snap.resolve("snapshots")))
        assertTrue(Files.readString(paths.targetsFile).contains("\"targets\""))
        assertTrue(Files.readString(paths.unitPath).contains("-jar ${paths.jarFile}"))
        assertTrue(!Files.readString(paths.unitPath).contains("rpcnode-cdn.jar sync"))
        assertEquals(
            listOf(
                listOf("systemctl", "--version"),
                listOf("systemctl", "daemon-reload"),
                listOf("systemctl", "enable", "rpcnode-cdn.service"),
                listOf("systemctl", "restart", "rpcnode-cdn.service"),
            ),
            ran,
        )
    }

    @Test
    fun install_requires_snapshot_dir()
    {
        val root = Files.createTempDirectory("cdn-install-nodir")
        val src = root.resolve("rpcnode-cdn.jar")
        Files.writeString(src, "jar")
        val code = CdnSystemInstall.install(
            paths = CdnSystemInstall.Paths(
                destDir = root.resolve("opt"),
                jarFile = root.resolve("opt/lib/rpcnode-cdn.jar"),
                envFile = root.resolve("etc/rpcnode-cdn.env"),
                targetsFile = root.resolve("etc/targets.json"),
                unitPath = root.resolve("systemd/rpcnode-cdn.service"),
                snapshotDir = null,
            ),
            selfJar = src,
            run = { 0 },
            out = {},
            err = {},
            isRoot = { true },
            chooseSnapshotDir = { null },
        )
        assertEquals(1, code)
    }
}
