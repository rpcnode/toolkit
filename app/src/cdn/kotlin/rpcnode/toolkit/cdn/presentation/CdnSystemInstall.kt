package rpcnode.toolkit.cdn.presentation

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Install / update rpcnode-cdn into systemd (root required).
 *
 *   sudo java -jar rpcnode-cdn.jar install
 */
object CdnSystemInstall
{
    data class Paths(
        val destDir: Path = Path.of("/opt/rpcnode"),
        val jarFile: Path = Path.of("/opt/rpcnode/lib/rpcnode-cdn.jar"),
        val envFile: Path = Path.of("/etc/rpcnode/rpcnode-cdn.env"),
        val targetsFile: Path = Path.of("/etc/rpcnode/rpcnode-cdn.targets.json"),
        val unitPath: Path = Path.of("/etc/systemd/system/rpcnode-cdn.service"),
        val snapshotDir: Path? = null,
        val unitName: String = "rpcnode-cdn.service",
        val pollSec: Long = 3600,
    )
    {
        companion object
        {
            fun fromEnv(
                env: (String) -> String? = { System.getenv(it) },
            ): Paths
            {
                val destDir = env("RPCNODE_CDN_HOME")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                    ?: Path.of("/opt/rpcnode")
                val snapshotDir = env("SNAPSHOT_CDN_DIR")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                val pollSec = env("CDN_POLL_SEC")?.toLongOrNull()?.coerceAtLeast(15) ?: 3600
                val envFile = env("CDN_ENV_FILE")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                    ?: Path.of("/etc/rpcnode/rpcnode-cdn.env")
                val targetsFile = env("CDN_TARGETS_FILE")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                    ?: Path.of("/etc/rpcnode/rpcnode-cdn.targets.json")
                val unitName = env("RPCNODE_CDN_UNIT")?.trim()?.ifEmpty { null } ?: "rpcnode-cdn.service"
                val fromFile = CdnEnvFile.read(envFile)?.snapshotDir?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                return Paths(
                    destDir = destDir,
                    jarFile = destDir.resolve("lib/rpcnode-cdn.jar"),
                    envFile = envFile,
                    targetsFile = targetsFile,
                    unitPath = Path.of("/etc/systemd/system/$unitName"),
                    snapshotDir = snapshotDir ?: fromFile,
                    unitName = unitName,
                    pollSec = pollSec,
                )
            }
        }
    }

    fun requireRoot(): Boolean
    {
        return try
        {
            val p = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor() == 0 && out == "0"
        }
        catch (_: Exception)
        {
            false
        }
    }

    fun resolveSelfJar(): Path?
    {
        val loc = CdnConfig::class.java.protectionDomain.codeSource?.location ?: return null
        return try
        {
            val path = Path.of(loc.toURI()).toAbsolutePath().normalize()
            if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar")) path else null
        }
        catch (_: Exception)
        {
            null
        }
    }

    fun resolveJavaBin(destDir: Path = Path.of("/opt/rpcnode")): String
    {
        val bundled = destDir.resolve("jdk/bin/java")
        if (Files.isExecutable(bundled))
        {
            return bundled.toString()
        }
        val fromProp = System.getProperty("java.home")?.let { Path.of(it, "bin", "java") }
        if (fromProp != null && Files.isExecutable(fromProp))
        {
            return fromProp.toAbsolutePath().normalize().toString()
        }
        val fromEnv = System.getenv("JAVA_HOME")?.let { Path.of(it, "bin", "java") }
        if (fromEnv != null && Files.isExecutable(fromEnv))
        {
            return fromEnv.toAbsolutePath().normalize().toString()
        }
        return "java"
    }

    fun envFileBody(snapshotDir: Path, pollSec: Long, targetsFile: Path): String = buildString {
        append("SNAPSHOT_CDN_DIR=").append(snapshotDir).append('\n')
        append("CDN_POLL_SEC=").append(pollSec).append('\n')
        append("CDN_TARGETS_FILE=").append(targetsFile).append('\n')
    }

    fun unitFileBody(
        javaBin: String,
        jarFile: Path,
        envFile: Path,
        workingDir: Path,
    ): String = """
        |[Unit]
        |Description=RpcNode Snapshot CDN sync
        |After=network-online.target
        |Wants=network-online.target
        |StartLimitIntervalSec=0
        |
        |[Service]
        |Type=simple
        |EnvironmentFile=$envFile
        |WorkingDirectory=$workingDir
        |ExecStart=$javaBin --enable-native-access=ALL-UNNAMED -jar $jarFile
        |Restart=always
        |RestartSec=5
        |LimitNOFILE=65536
        |
        |[Install]
        |WantedBy=multi-user.target
        |""".trimMargin()

    fun install(
        paths: Paths = Paths.fromEnv(),
        selfJar: Path? = resolveSelfJar(),
        javaBin: String = resolveJavaBin(paths.destDir),
        run: (List<String>) -> Int = ::runCommand,
        out: (String) -> Unit = { println("  $it") },
        err: (String) -> Unit = { System.err.println("ERROR: $it") },
        isRoot: () -> Boolean = ::requireRoot,
        chooseSnapshotDir: (() -> Path?)? = null,
    ): Int
    {
        if (!isRoot())
        {
            err("run as root: sudo java -jar rpcnode-cdn.jar install")
            return 1
        }
        if (run(listOf("systemctl", "--version")) != 0)
        {
            err("systemctl is required")
            return 1
        }
        val src = selfJar
        if (src == null || !Files.isRegularFile(src))
        {
            err("could not locate the running rpcnode-cdn.jar")
            return 1
        }
        val snapshotDir = paths.snapshotDir
            ?: (chooseSnapshotDir ?: {
                CdnSnapshotDirPicker.pick(current = CdnSnapshotDirPicker.launchCwd())
            })()
            ?: run {
                err("SNAPSHOT_CDN_DIR required — pick a disk/folder (default: current directory)")
                return 1
            }
        Files.createDirectories(paths.jarFile.parent)
        Files.createDirectories(paths.envFile.parent)
        Files.createDirectories(paths.targetsFile.parent)
        Files.createDirectories(snapshotDir)
        Files.createDirectories(snapshotDir.resolve("snapshots"))
        out("copying $src → ${paths.jarFile}")
        Files.copy(src, paths.jarFile, StandardCopyOption.REPLACE_EXISTING)
        if (!Files.isRegularFile(paths.targetsFile))
        {
            Files.writeString(paths.targetsFile, """{"targets":[]}""" + "\n")
            setMode(paths.targetsFile, "rw-r--r--")
        }
        Files.writeString(
            paths.envFile,
            envFileBody(snapshotDir, paths.pollSec, paths.targetsFile),
        )
        setMode(paths.envFile, "rw-------")
        Files.writeString(
            paths.unitPath,
            unitFileBody(javaBin, paths.jarFile, paths.envFile, paths.destDir),
        )
        if (run(listOf("systemctl", "daemon-reload")) != 0)
        {
            err("systemctl daemon-reload failed")
            return 1
        }
        if (run(listOf("systemctl", "enable", paths.unitName)) != 0)
        {
            err("systemctl enable ${paths.unitName} failed")
            return 1
        }
        if (run(listOf("systemctl", "restart", paths.unitName)) != 0)
        {
            err("systemctl restart ${paths.unitName} failed")
            return 1
        }
        out("unit ${paths.unitName} started")
        out("snapshots → ${snapshotDir}/snapshots")
        out("targets  → ${paths.targetsFile}")
        out("configure: sudo java -jar ${paths.jarFile} menu")
        out("status:    sudo java -jar ${paths.jarFile} status")
        return 0
    }

    fun uninstall(
        paths: Paths = Paths.fromEnv(),
        run: (List<String>) -> Int = ::runCommand,
        out: (String) -> Unit = { println("  $it") },
        err: (String) -> Unit = { System.err.println("ERROR: $it") },
        isRoot: () -> Boolean = ::requireRoot,
    ): Int
    {
        if (!isRoot())
        {
            err("run as root: sudo java -jar rpcnode-cdn.jar uninstall")
            return 1
        }
        run(listOf("systemctl", "stop", paths.unitName))
        run(listOf("systemctl", "disable", paths.unitName))
        Files.deleteIfExists(paths.unitPath)
        Files.deleteIfExists(paths.jarFile)
        Files.deleteIfExists(paths.envFile)
        run(listOf("systemctl", "daemon-reload"))
        out("removed unit and jar (kept snapshots and ${paths.targetsFile})")
        return 0
    }

    private fun setMode(path: Path, posix: String)
    {
        try
        {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString(posix))
        }
        catch (_: Exception)
        {
            // non-POSIX FS — ignore
        }
    }

    private fun runCommand(cmd: List<String>): Int
    {
        return try
        {
            val p = ProcessBuilder(cmd).inheritIO().start()
            p.waitFor()
        }
        catch (_: Exception)
        {
            127
        }
    }
}
