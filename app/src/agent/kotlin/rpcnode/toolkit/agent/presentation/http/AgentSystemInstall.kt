package rpcnode.toolkit.agent.presentation.http

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Duration
import rpcnode.toolkit.agent.domain.model.AGENT_API_PORT
import rpcnode.toolkit.agent.infrastructure.proc.EnsureHostCurl
import rpcnode.toolkit.agent.infrastructure.proc.runningAsRoot

/**
 * Install / update / uninstall rpcnode-agent into systemd (root required).
 *
 *   curl -fsSL -o rpcnode-agent.jar "$ORIGIN/install/binaries/rpcnode-agent.jar"
 *   sudo java -jar rpcnode-agent.jar install
 */
object AgentSystemInstall
{
    data class Paths(
        val destDir: Path = Path.of("/opt/rpcnode"),
        val jarFile: Path = Path.of("/opt/rpcnode/lib/rpcnode-agent.jar"),
        val envFile: Path = Path.of("/etc/rpcnode/rpcnode-agent.env"),
        val tokenFile: Path = Path.of("/etc/rpcnode/agent.token"),
        val portFile: Path = Path.of("/etc/rpcnode/rpcnode-agent.port"),
        val rangeFile: Path = Path.of("/etc/rpcnode/rpcnode-agent.ports"),
        val unitPath: Path = Path.of("/etc/systemd/system/rpcnode-agent.service"),
        val sysctlConf: Path = Path.of("/etc/sysctl.d/99-rpcnode-agent-ports.conf"),
        val unitName: String = "rpcnode-agent.service",
        val port: Int = AGENT_API_PORT,
    )
    {
        companion object
        {
            fun fromEnv(
                env: (String) -> String? = { System.getenv(it) },
            ): Paths
            {
                val destDir = env("RPCNODE_AGENT_HOME")?.trim()?.ifEmpty { null }
                    ?.let { Path.of(it) }
                    ?: env("CHAIN_AGENT_HOME")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                    ?: Path.of("/opt/rpcnode")
                val unitName = env("RPCNODE_AGENT_UNIT")?.trim()?.ifEmpty { null }
                    ?: env("CHAIN_AGENT_UNIT")?.trim()?.ifEmpty { null }
                    ?: "rpcnode-agent.service"
                val envFile = env("AGENT_ENV_FILE")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                    ?: Path.of("/etc/rpcnode/rpcnode-agent.env")
                val tokenFile = env("AGENT_TOKEN_FILE")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                    ?: Path.of("/etc/rpcnode/agent.token")
                val portFile = env("AGENT_PORT_FILE")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                    ?: Path.of("/etc/rpcnode/rpcnode-agent.port")
                val rangeFile = env("AGENT_RANGE_FILE")?.trim()?.ifEmpty { null }?.let { Path.of(it) }
                    ?: Path.of("/etc/rpcnode/rpcnode-agent.ports")
                val port = env("AGENT_PORT")?.toIntOrNull()
                    ?: readPortFile(portFile)
                    ?: AGENT_API_PORT
                return Paths(
                    destDir = destDir,
                    jarFile = destDir.resolve("lib/rpcnode-agent.jar"),
                    envFile = envFile,
                    tokenFile = tokenFile,
                    portFile = portFile,
                    rangeFile = rangeFile,
                    unitPath = Path.of("/etc/systemd/system/$unitName"),
                    unitName = unitName,
                    port = port,
                )
            }
        }
    }

    fun resolveSelfJar(): Path?
    {
        val loc = AgentConfig::class.java.protectionDomain.codeSource?.location ?: return null
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

    fun envFileBody(
        port: Int,
        tokenFile: Path,
        rangeFile: Path,
        sysctlConf: Path,
    ): String = buildString {
        append("AGENT_PORT=").append(port).append('\n')
        append("AGENT_TOKEN_FILE=").append(tokenFile).append('\n')
        append("AGENT_RANGE_FILE=").append(rangeFile).append('\n')
        append("AGENT_SYSCTL_CONF=").append(sysctlConf).append('\n')
    }

    fun unitFileBody(
        javaBin: String,
        jarFile: Path,
        envFile: Path,
        workingDir: Path,
    ): String = """
        |[Unit]
        |Description=RpcNode host agent
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
        |RestartSec=2
        |
        |[Install]
        |WantedBy=multi-user.target
        |""".trimMargin()

    fun genToken(random: SecureRandom = SecureRandom()): String
    {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { b -> "%02x".format(b) }
    }

    fun install(
        paths: Paths = Paths.fromEnv(),
        selfJar: Path? = resolveSelfJar(),
        javaBin: String = resolveJavaBin(paths.destDir),
        run: (List<String>) -> Int = ::runCommand,
        out: (String) -> Unit = { println("  $it") },
        err: (String) -> Unit = { System.err.println("ERROR: $it") },
        isRoot: () -> Boolean = { runningAsRoot() },
        ensureHostDeps: () -> Unit = ::ensureHostRuntimeDeps,
        waitHealthy: (port: Int, token: String) -> Boolean = ::waitHealthz,
        hostIp: () -> String = ::detectHostIp,
        sleepMs: (Long) -> Unit = { Thread.sleep(it) },
        requireExisting: Boolean = false,
    ): Int
    {
        if (!isRoot())
        {
            err("run as root: sudo java -jar rpcnode-agent.jar install")
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
            err("could not locate the running rpcnode-agent.jar")
            return 1
        }
        if (requireExisting && !alreadyInstalled(paths))
        {
            err("rpcnode-agent is not installed — run: sudo java -jar rpcnode-agent.jar install")
            return 1
        }
        try
        {
            ensureHostDeps()
        }
        catch (e: Exception)
        {
            err(e.message ?: "host deps failed")
            return 1
        }
        adoptLegacy(paths, out)
        freeAgentPort(paths, run, out, sleepMs)
        var stillMasked = true
        try
        {
            Files.createDirectories(paths.jarFile.parent)
            Files.createDirectories(paths.envFile.parent)
            out("copying $src → ${paths.jarFile}")
            Files.copy(src, paths.jarFile, StandardCopyOption.REPLACE_EXISTING)
            writePort(paths.portFile, paths.port)
            val token = ensureToken(paths.tokenFile)
            Files.writeString(
                paths.envFile,
                envFileBody(paths.port, paths.tokenFile, paths.rangeFile, paths.sysctlConf),
            )
            setMode(paths.envFile, "rw-------")
            Files.writeString(
                paths.unitPath,
                unitFileBody(javaBin, paths.jarFile, paths.envFile, paths.destDir),
            )
            dropLegacy(paths, run)
            unmaskUnit(paths, run)
            stillMasked = false
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
                run(listOf("journalctl", "-u", paths.unitName, "-n", "40", "--no-pager"))
                return 1
            }
            if (!waitHealthy(paths.port, token))
            {
                err("rpcnode-agent API health check failed: http://127.0.0.1:${paths.port}/healthz")
                run(listOf("journalctl", "-u", paths.unitName, "-n", "40", "--no-pager"))
                return 1
            }
            val ip = hostIp().ifBlank { "127.0.0.1" }
            out("unit ${paths.unitName} started")
            out("jar  ${paths.jarFile}")
            out("port ${paths.port}")
            println()
            println("  Agent URL :  http://$ip:${paths.port}")
            println("  Agent key :  $token")
            println()
            println("paste those into the admin (Servers → Add server).")
            println()
            return 0
        }
        finally
        {
            if (stillMasked)
            {
                unmaskUnit(paths, run)
            }
        }
    }

    fun update(
        paths: Paths = Paths.fromEnv(),
        selfJar: Path? = resolveSelfJar(),
        javaBin: String = resolveJavaBin(paths.destDir),
        run: (List<String>) -> Int = ::runCommand,
        out: (String) -> Unit = { println("  $it") },
        err: (String) -> Unit = { System.err.println("ERROR: $it") },
        isRoot: () -> Boolean = { runningAsRoot() },
        ensureHostDeps: () -> Unit = ::ensureHostRuntimeDeps,
        waitHealthy: (port: Int, token: String) -> Boolean = ::waitHealthz,
        hostIp: () -> String = ::detectHostIp,
        sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    ): Int = install(
        paths = paths,
        selfJar = selfJar,
        javaBin = javaBin,
        run = run,
        out = out,
        err = err,
        isRoot = isRoot,
        ensureHostDeps = ensureHostDeps,
        waitHealthy = waitHealthy,
        hostIp = hostIp,
        sleepMs = sleepMs,
        requireExisting = true,
    )

    fun uninstall(
        paths: Paths = Paths.fromEnv(),
        run: (List<String>) -> Int = ::runCommand,
        out: (String) -> Unit = { println("  $it") },
        err: (String) -> Unit = { System.err.println("ERROR: $it") },
        isRoot: () -> Boolean = { runningAsRoot() },
        sleepMs: (Long) -> Unit = { Thread.sleep(it) },
    ): Int
    {
        if (!isRoot())
        {
            err("run as root: sudo java -jar rpcnode-agent.jar uninstall")
            return 1
        }
        out("removing rpcnode-agent")
        freeAgentPort(paths, run, out, sleepMs)
        run(listOf("systemctl", "disable", paths.unitName))
        unmaskUnit(paths, run)
        dropLegacy(paths, run)
        Files.deleteIfExists(paths.unitPath)
        Files.deleteIfExists(paths.jarFile)
        Files.deleteIfExists(paths.envFile)
        Files.deleteIfExists(paths.tokenFile)
        Files.deleteIfExists(paths.portFile)
        Files.deleteIfExists(paths.rangeFile)
        Files.deleteIfExists(paths.sysctlConf)
        Files.deleteIfExists(paths.destDir.resolve("bin/gum"))
        run(listOf("systemctl", "daemon-reload"))
        out("rpcnode-agent removed")
        out("Re-install: sudo java -jar rpcnode-agent.jar install")
        return 0
    }

    fun alreadyInstalled(paths: Paths): Boolean
    {
        return Files.isRegularFile(paths.unitPath)
            || Files.isRegularFile(paths.jarFile)
            || Files.isRegularFile(paths.envFile)
            || Files.isRegularFile(Path.of("/etc/systemd/system/chain-agent.service"))
            || Files.isRegularFile(paths.destDir.resolve("lib/chain-agent.jar"))
    }

    internal fun ensureToken(tokenFile: Path, random: SecureRandom = SecureRandom()): String
    {
        Files.createDirectories(tokenFile.parent)
        if (Files.isRegularFile(tokenFile) && Files.size(tokenFile) > 0)
        {
            return Files.readString(tokenFile).trim()
        }
        val token = genToken(random)
        Files.writeString(tokenFile, token + "\n")
        setMode(tokenFile, "rw-------")
        return token
    }

    private fun writePort(portFile: Path, port: Int)
    {
        Files.createDirectories(portFile.parent)
        Files.writeString(portFile, "$port\n")
        setMode(portFile, "rw-r--r--")
    }

    private fun adoptLegacy(paths: Paths, out: (String) -> Unit)
    {
        val legacyPort = Path.of("/etc/rpcnode/chain-agent.port")
        val legacyEnv = Path.of("/etc/rpcnode/chain-agent.env")
        val legacyRange = Path.of("/etc/rpcnode/chain-agent.ports")
        Files.createDirectories(Path.of("/etc/rpcnode"))
        copyIfMissing(legacyPort, paths.portFile, out)
        copyIfMissing(legacyEnv, paths.envFile, out)
        copyIfMissing(legacyRange, paths.rangeFile, out)
        if (Files.isRegularFile(paths.envFile))
        {
            setMode(paths.envFile, "rw-------")
        }
    }

    private fun dropLegacy(paths: Paths, run: (List<String>) -> Int)
    {
        val legacyUnit = "chain-agent.service"
        if (paths.unitName != legacyUnit)
        {
            run(listOf("systemctl", "stop", legacyUnit))
            run(listOf("systemctl", "disable", legacyUnit))
            Files.deleteIfExists(Path.of("/etc/systemd/system/$legacyUnit"))
        }
        Files.deleteIfExists(paths.destDir.resolve("lib/chain-agent.jar"))
        Files.deleteIfExists(Path.of("/etc/rpcnode/chain-agent.env"))
        Files.deleteIfExists(Path.of("/etc/rpcnode/chain-agent.port"))
        Files.deleteIfExists(Path.of("/etc/rpcnode/chain-agent.ports"))
        Files.deleteIfExists(Path.of("/etc/sysctl.d/99-rpcnode-zz-chain-agent-ports.conf"))
    }

    private fun freeAgentPort(
        paths: Paths,
        run: (List<String>) -> Int,
        out: (String) -> Unit,
        sleepMs: (Long) -> Unit,
    )
    {
        out("checking port ${paths.port}…")
        run(listOf("systemctl", "mask", "--runtime", paths.unitName))
        run(listOf("systemctl", "stop", paths.unitName))
        if (paths.unitName != "chain-agent.service")
        {
            run(listOf("systemctl", "mask", "--runtime", "chain-agent.service"))
            run(listOf("systemctl", "stop", "chain-agent.service"))
        }
        // Quiet: unit may not be loaded yet on a fresh host.
        run(listOf("sh", "-c", "systemctl reset-failed ${paths.unitName} >/dev/null 2>&1 || true"))
        // Never pkill -f the jar name: that matches `java -jar rpcnode-agent.jar install`
        // and kills the installer itself (left units runtime-masked, no unit file written).
        killOtherAgentProcesses()
        sleepMs(500)
        out("port ${paths.port} ready")
    }

    /**
     * Stop other JVMs running the agent jar / main, but never the current install/update process.
     */
    internal fun killOtherAgentProcesses(
        selfPid: Long = ProcessHandle.current().pid(),
        candidates: () -> Sequence<Pair<Long, String>> = {
            ProcessHandle.allProcesses().iterator().asSequence().mapNotNull { ph ->
                val cmd = ph.info().commandLine().orElse(null) ?: return@mapNotNull null
                ph.pid() to cmd
            }
        },
        destroyPid: (Long) -> Unit = { pid ->
            ProcessHandle.of(pid).ifPresent { handle ->
                try
                {
                    handle.destroy()
                }
                catch (_: Exception)
                {
                    // best-effort
                }
            }
        },
    )
    {
        val markers = listOf(
            "rpcnode-agent.jar",
            "chain-agent.jar",
            "rpcnode.toolkit.agent.presentation.http.AgentMainKt",
        )
        for ((pid, cmd) in candidates())
        {
            if (pid == selfPid)
            {
                continue
            }
            if (markers.none { marker -> cmd.contains(marker) })
            {
                continue
            }
            try
            {
                destroyPid(pid)
            }
            catch (_: Exception)
            {
                // best-effort
            }
        }
    }

    private fun unmaskUnit(paths: Paths, run: (List<String>) -> Int)
    {
        run(listOf("systemctl", "unmask", paths.unitName))
        if (paths.unitName != "chain-agent.service")
        {
            run(listOf("systemctl", "unmask", "chain-agent.service"))
        }
    }

    private fun ensureHostRuntimeDeps()
    {
        rpcnode.toolkit.agent.infrastructure.proc.EnsureHostDownloader.ensure()
        if (!EnsureHostCurl.onPath("tar"))
        {
            val mgr = EnsureHostCurl.detectPkgMgr()
                ?: error("need tar and no package manager was found")
            mgr.install("tar")
            if (!EnsureHostCurl.onPath("tar"))
            {
                error("could not install tar")
            }
        }
    }

    private fun waitHealthz(port: Int, token: String): Boolean
    {
        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build()
        val req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port/healthz"))
            .timeout(Duration.ofSeconds(2))
            .header("Authorization", "Bearer $token")
            .GET()
            .build()
        repeat(20)
        {
            try
            {
                val res = client.send(req, HttpResponse.BodyHandlers.ofString())
                if (res.statusCode() in 200..299 && res.body().contains("\"alive\":true"))
                {
                    return true
                }
            }
            catch (_: Exception)
            {
                // still starting
            }
            Thread.sleep(500)
        }
        return false
    }

    private fun detectHostIp(): String
    {
        return try
        {
            val p = ProcessBuilder("sh", "-c", "ip -4 -o addr show scope global 2>/dev/null | awk '{print \$4}' | cut -d/ -f1 | head -1")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && out.isNotBlank())
            {
                return out
            }
            InetAddress.getLocalHost().hostAddress ?: "127.0.0.1"
        }
        catch (_: Exception)
        {
            "127.0.0.1"
        }
    }

    private fun copyIfMissing(from: Path, to: Path, out: (String) -> Unit)
    {
        if (!Files.isRegularFile(to) && Files.isRegularFile(from) && Files.size(from) > 0)
        {
            Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
            out("adopted legacy $from → $to")
        }
    }

    private fun readPortFile(portFile: Path): Int?
    {
        if (!Files.isRegularFile(portFile))
        {
            return null
        }
        return try
        {
            Files.readString(portFile).trim().toIntOrNull()
        }
        catch (_: Exception)
        {
            null
        }
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
