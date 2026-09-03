package rpcnode.toolkit.nodes.infrastructure.host

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/**
 * Shared host helpers for chain starters: extract archive, render network systemd template,
 * install unit, enable+restart. Stdout/stderr → `{nodeDir}/logs/node.out`.
 * Requires root (systemctl / /etc/systemd/system).
 *
 * Templates live at classpath `chains/<network>/node.service.tmpl` (fallback `chains/default/`).
 * Launch recipe is persisted as `{nodeDir}/.toolkit/launch.json` so Sync Start can always
 * re-apply the current template and restart.
 */
object HostNodeLaunchSupport
{
    private val log = LoggerFactory.getLogger(HostNodeLaunchSupport::class.java)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun startProcess(
        nodeId: String,
        network: String,
        env: String,
        nodeDir: Path,
        launch: NodeLaunchSpec,
    ): HostNodeStartResult
    {
        if (!Files.isDirectory(nodeDir))
        {
            return HostNodeStartResult.Failed("node_dir missing: $nodeDir")
        }
        if (launch.kind.isBlank() || launch.entry.isBlank())
        {
            return HostNodeStartResult.InvalidLaunch
        }
        val prepared = prepareEntry(nodeDir, launch.entry, launch.extractArchiveGlob, launch.normalizeDir)
        if (prepared != null)
        {
            return HostNodeStartResult.Failed(prepared)
        }
        val record = HostNodeLaunchRecord(
            nodeId = nodeId.trim(),
            network = network.trim().lowercase(),
            env = env.trim().lowercase(),
            kind = launch.kind.trim(),
            entry = launch.entry.trim(),
            args = launch.args,
            extractArchiveGlob = launch.extractArchiveGlob,
            normalizeDir = launch.normalizeDir,
            javaMajor = launch.javaMajor,
            logFile = launch.logFile,
        )
        return installAndRestart(record, nodeDir)
    }

    /** `rpcnode-<network>-<env>.service` — one unit per network/env on the host. */
    fun unitName(network: String, env: String): String
    {
        val n = sanitizeUnitPart(network).ifEmpty { "unknown" }
        val e = sanitizeUnitPart(env).ifEmpty { "unknown" }
        return "rpcnode-$n-$e.service"
    }

    fun mainPid(unit: String): Long?
    {
        val show = runSystemctl("show", "-p", "MainPID", "--value", unit)
        if (show.exit != 0)
        {
            return null
        }
        return show.out.trim().toLongOrNull()?.takeIf { it > 0L }
    }

    /**
     * Preferred network/env name, else `.toolkit/systemd.unit` from a previous install
     * (covers older UUID-named units until the next rewrite).
     */
    fun resolveUnit(network: String, env: String, nodeDir: Path?): String
    {
        val preferred = unitName(network, env)
        if (Files.isRegularFile(Path.of("/etc/systemd/system", preferred)))
        {
            return preferred
        }
        val recorded = readRecordedUnitName(nodeDir)
        if (recorded != null && Files.isRegularFile(Path.of("/etc/systemd/system", recorded)))
        {
            return recorded
        }
        return preferred
    }

    /** Stop an already-installed node unit (Sync step Stop). Companion units first. */
    fun stopUnit(network: String, env: String, nodeDir: Path? = null): HostNodeStartResult
    {
        for (companion in readCompanions(nodeDir).asReversed())
        {
            val stopped = stopOneUnit(companion, network, env)
            if (stopped is HostNodeStartResult.Failed)
            {
                return stopped
            }
        }
        val unit = resolveUnit(network, env, nodeDir)
        return stopOneUnit(unit, network, env)
    }

    private fun stopOneUnit(unit: String, network: String, env: String): HostNodeStartResult
    {
        if (!Files.isRegularFile(Path.of("/etc/systemd/system", unit)))
        {
            log.info("systemd unit={} already absent ({} / {})", unit, network, env)
            return HostNodeStartResult.Started(pid = 0)
        }
        val stop = runSystemctl("stop", unit)
        if (stop.exit != 0)
        {
            return HostNodeStartResult.Failed("systemctl stop $unit failed: ${stop.out}")
        }
        var i = 0
        while (i < 25)
        {
            val active = runSystemctl("is-active", unit).out.trim()
            if (active != "active" && active != "activating")
            {
                log.info("systemd unit={} stopped ({} / {})", unit, network, env)
                return HostNodeStartResult.Started(pid = 0)
            }
            Thread.sleep(200)
            i++
        }
        runSystemctl("kill", "-s", "SIGKILL", unit)
        runSystemctl("stop", unit)
        log.info("systemd unit={} force-stopped ({} / {})", unit, network, env)
        return HostNodeStartResult.Started(pid = 0)
    }

    /**
     * Stop, disable, and delete `rpcnode-<network>-<env>.service` (+ companions) on node remove.
     */
    fun removeUnit(network: String, env: String, nodeDir: Path? = null): HostNodeStartResult
    {
        val unit = resolveUnit(network, env, nodeDir)
        val companions = readCompanions(nodeDir)
        val stopped = stopUnit(network, env, nodeDir)
        if (stopped is HostNodeStartResult.Failed)
        {
            return stopped
        }
        val toRemove = companions + unit
        for (name in toRemove.distinct())
        {
            val unitPath = Path.of("/etc/systemd/system", name)
            if (!Files.isRegularFile(unitPath))
            {
                continue
            }
            val disable = runSystemctl("disable", "--now", name)
            if (disable.exit != 0)
            {
                log.warn("systemctl disable {} : {}", name, disable.out.trim())
            }
            try
            {
                Files.deleteIfExists(unitPath)
            }
            catch (e: Exception)
            {
                return HostNodeStartResult.Failed("delete unit $name failed: ${e.message}")
            }
            runSystemctl("reset-failed", name)
        }
        val reload = runSystemctl("daemon-reload")
        if (reload.exit != 0)
        {
            return HostNodeStartResult.Failed("systemctl daemon-reload failed: ${reload.out}")
        }
        if (nodeDir != null)
        {
            runCatching { Files.deleteIfExists(nodeDir.resolve(".toolkit/systemd.unit")) }
            runCatching { Files.deleteIfExists(nodeDir.resolve(".toolkit/systemd.companions")) }
            runCatching { Files.deleteIfExists(nodeDir.resolve(".toolkit/systemd.unit.body")) }
            for (c in companions)
            {
                runCatching { Files.deleteIfExists(companionBodyPath(nodeDir, c)) }
            }
        }
        log.info("systemd unit={} removed ({} / {})", unit, network, env)
        return HostNodeStartResult.Started(pid = 0)
    }

    /**
     * Delete all files under [nodeDir] (keep the directory itself — may be a mount leaf).
     */
    fun wipeNodeDir(nodeDir: Path): HostNodeStartResult
    {
        val abs = try
        {
            nodeDir.toAbsolutePath().normalize()
        }
        catch (_: Exception)
        {
            return HostNodeStartResult.Failed("invalid node_dir")
        }
        val path = abs.toString()
        if (
            path.length < 8 ||
            path == "/" ||
            path == "/mnt" ||
            path == "/data" ||
            path == "/home" ||
            path == "/var" ||
            path == "/etc" ||
            path == "/opt" ||
            ".." in path
        )
        {
            return HostNodeStartResult.Failed("refusing to wipe unsafe node_dir: $path")
        }
        if (!Files.isDirectory(abs))
        {
            log.info("node_dir {} already absent — nothing to wipe", abs)
            return HostNodeStartResult.Started(pid = 0)
        }
        return try
        {
            Files.walk(abs).use { stream ->
                stream
                    .sorted(Comparator.reverseOrder())
                    .filter { it != abs }
                    .forEach { Files.deleteIfExists(it) }
            }
            log.info("wiped node_dir {}", abs)
            HostNodeStartResult.Started(pid = 0)
        }
        catch (e: Exception)
        {
            HostNodeStartResult.Failed("wipe node_dir failed: ${e.message}")
        }
    }

    /**
     * Sync Start: always re-apply the current network systemd template (when launch.json
     * exists) and `systemctl restart`. Falls back to restart-only if the unit is present
     * but launch metadata is missing.
     */
    fun restartUnit(network: String, env: String, nodeDir: Path? = null): HostNodeStartResult
    {
        val net = network.trim().lowercase()
        val envId = env.trim().lowercase()
        if (nodeDir != null && Files.isDirectory(nodeDir))
        {
            val record = readLaunchRecord(nodeDir)
            if (record != null)
            {
                val merged = record.copy(
                    network = record.network.ifBlank { net },
                    env = record.env.ifBlank { envId },
                )
                return installAndRestart(merged, nodeDir)
            }
        }
        val unit = resolveUnit(net, envId, nodeDir)
        val unitPath = Path.of("/etc/systemd/system", unit)
        if (!Files.isRegularFile(unitPath))
        {
            return HostNodeStartResult.Failed(
                "unit $unit is not installed and no .toolkit/launch.json — run Start from the wizard first",
            )
        }
        val restart = runSystemctl("restart", unit)
        if (restart.exit != 0)
        {
            return HostNodeStartResult.Failed("systemctl restart $unit failed: ${restart.out}")
        }
        return waitActive(unit, net, envId)
    }

    private fun installAndRestart(record: HostNodeLaunchRecord, nodeDir: Path): HostNodeStartResult
    {
        val network = record.network.trim().lowercase()
        val env = record.env.trim().lowercase()
        if (network.isEmpty() || env.isEmpty())
        {
            return HostNodeStartResult.Failed("network and env required for systemd unit")
        }
        if (record.kind.isBlank() || record.entry.isBlank())
        {
            return HostNodeStartResult.InvalidLaunch
        }
        val prepared = prepareEntry(nodeDir, record.entry, record.extractArchiveGlob, record.normalizeDir)
        if (prepared != null)
        {
            return HostNodeStartResult.Failed(prepared)
        }
        val entry = nodeDir.resolve(record.entry)
        if (!Files.exists(entry))
        {
            return HostNodeStartResult.Failed("launch entry missing: ${record.entry}")
        }
        val logDir = nodeDir.resolve("logs")
        try
        {
            Files.createDirectories(logDir)
            Files.createDirectories(nodeDir.resolve(".toolkit"))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir logs failed")
        }
        val logFile = logDir.resolve("node.out").toAbsolutePath()
        val absNodeDir = nodeDir.toAbsolutePath()
        val argv = when (record.kind.trim().lowercase())
        {
            "java_jar" ->
            {
                val required = record.javaMajor
                when (val java = HostJavaBinary.resolve(required))
                {
                    is HostJavaBinary.ResolveResult.Found ->
                        listOf(java.path, "-jar", entry.fileName.toString()) + record.args
                    is HostJavaBinary.ResolveResult.Missing ->
                        return HostNodeStartResult.Failed(java.detail)
                }
            }
            "binary" -> listOf(entry.toAbsolutePath().toString()) + record.args
            else -> return HostNodeStartResult.Failed("unknown launch kind: ${record.kind}")
        }
        val unit = unitName(network, env)
        val unitPath = Path.of("/etc/systemd/system", unit)
        val description = "rpcnode $network/$env (${record.nodeId})"
        val customBody = nodeDir.resolve(".toolkit/systemd.unit.body")
        val unitBody = if (Files.isRegularFile(customBody))
        {
            Files.readString(customBody)
        }
        else
        {
            HostSystemdUnitTemplate.render(
                HostSystemdUnitTemplate.load(network),
                mapOf(
                    "description" to description,
                    "network" to network,
                    "env" to env,
                    "node_id" to record.nodeId,
                    "node_dir" to absNodeDir.toString(),
                    "log_file" to logFile.toString(),
                    "exec_start" to argv.joinToString(" ") { escapeSystemdArg(it) },
                ),
            )
        }
        return try
        {
            Files.writeString(unitPath, unitBody)
            Files.writeString(nodeDir.resolve(".toolkit/systemd.unit"), "$unit\n")
            Files.writeString(nodeDir.resolve(".toolkit/launch.json"), json.encodeToString(record) + "\n")
            for (companion in readCompanions(nodeDir))
            {
                val bodyFile = companionBodyPath(nodeDir, companion)
                if (!Files.isRegularFile(bodyFile))
                {
                    continue
                }
                Files.writeString(Path.of("/etc/systemd/system", companion), Files.readString(bodyFile))
            }
            val reload = runSystemctl("daemon-reload")
            if (reload.exit != 0)
            {
                return HostNodeStartResult.Failed("systemctl daemon-reload failed: ${reload.out}")
            }
            val companions = readCompanions(nodeDir)
            fun enableAndRestart(name: String): HostNodeStartResult?
            {
                val enable = runSystemctl("enable", name)
                if (enable.exit != 0)
                {
                    return HostNodeStartResult.Failed("systemctl enable $name failed: ${enable.out}")
                }
                val restart = runSystemctl("restart", name)
                if (restart.exit != 0)
                {
                    return HostNodeStartResult.Failed("systemctl restart $name failed: ${restart.out}")
                }
                return null
            }
            if (startCompanionsFirst(nodeDir))
            {
                for (companion in companions)
                {
                    enableAndRestart(companion)?.let { return it }
                }
                enableAndRestart(unit)?.let { return it }
            }
            else
            {
                enableAndRestart(unit)?.let { return it }
                for (companion in companions)
                {
                    enableAndRestart(companion)?.let { return it }
                }
            }
            val started = waitActive(unit, network, env)
            if (started is HostNodeStartResult.Started)
            {
                log.info(
                    "node {} systemd unit={} network={} env={} pid={} kind={} entry={} (template re-applied)",
                    record.nodeId,
                    unit,
                    network,
                    env,
                    started.pid,
                    record.kind,
                    record.entry,
                )
            }
            started
        }
        catch (e: Exception)
        {
            HostNodeStartResult.Failed(e.message ?: "systemd start failed")
        }
    }

    /**
     * Install primary + optional companion units from pre-rendered bodies (multi-process chains).
     * Persists bodies under `.toolkit/` so Sync Start / restart re-applies them.
     */
    fun installCustomUnits(
        nodeId: String,
        network: String,
        env: String,
        nodeDir: Path,
        primaryBody: String,
        companions: List<Pair<String, String>>,
        launch: NodeLaunchSpec,
        startCompanionsFirst: Boolean = false,
    ): HostNodeStartResult
    {
        val net = network.trim().lowercase()
        val envId = env.trim().lowercase()
        if (net.isEmpty() || envId.isEmpty())
        {
            return HostNodeStartResult.Failed("network and env required for systemd unit")
        }
        try
        {
            Files.createDirectories(nodeDir.resolve("logs"))
            Files.createDirectories(nodeDir.resolve(".toolkit"))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir failed")
        }
        val unit = unitName(net, envId)
        val record = HostNodeLaunchRecord(
            nodeId = nodeId.trim(),
            network = net,
            env = envId,
            kind = launch.kind.trim(),
            entry = launch.entry.trim(),
            args = launch.args,
            extractArchiveGlob = launch.extractArchiveGlob,
            normalizeDir = launch.normalizeDir,
            javaMajor = launch.javaMajor,
            logFile = launch.logFile,
        )
        return try
        {
            Files.writeString(nodeDir.resolve(".toolkit/systemd.unit.body"), primaryBody)
            Files.writeString(Path.of("/etc/systemd/system", unit), primaryBody)
            Files.writeString(nodeDir.resolve(".toolkit/systemd.unit"), "$unit\n")
            val companionNames = companions.map { it.first.trim() }.filter { it.isNotEmpty() }
            Files.writeString(
                nodeDir.resolve(".toolkit/systemd.companions"),
                companionNames.joinToString("\n") + if (companionNames.isEmpty()) "" else "\n",
            )
            Files.writeString(
                nodeDir.resolve(".toolkit/systemd.start_companions_first"),
                if (startCompanionsFirst) "1\n" else "0\n",
            )
            for ((name, body) in companions)
            {
                val n = name.trim()
                if (n.isEmpty()) continue
                Files.writeString(companionBodyPath(nodeDir, n), body)
                Files.writeString(Path.of("/etc/systemd/system", n), body)
            }
            Files.writeString(nodeDir.resolve(".toolkit/launch.json"), json.encodeToString(record) + "\n")
            val reload = runSystemctl("daemon-reload")
            if (reload.exit != 0)
            {
                return HostNodeStartResult.Failed("systemctl daemon-reload failed: ${reload.out}")
            }
            fun enableAndRestart(name: String): HostNodeStartResult?
            {
                val enable = runSystemctl("enable", name)
                if (enable.exit != 0)
                {
                    return HostNodeStartResult.Failed("systemctl enable $name failed: ${enable.out}")
                }
                val restart = runSystemctl("restart", name)
                if (restart.exit != 0)
                {
                    return HostNodeStartResult.Failed("systemctl restart $name failed: ${restart.out}")
                }
                return null
            }
            if (startCompanionsFirst)
            {
                for (name in companionNames)
                {
                    enableAndRestart(name)?.let { return it }
                }
                enableAndRestart(unit)?.let { return it }
            }
            else
            {
                enableAndRestart(unit)?.let { return it }
                for (name in companionNames)
                {
                    enableAndRestart(name)?.let { return it }
                }
            }
            waitActive(unit, net, envId)
        }
        catch (e: Exception)
        {
            HostNodeStartResult.Failed(e.message ?: "systemd start failed")
        }
    }

    fun startCompanionsFirst(nodeDir: Path?): Boolean
    {
        if (nodeDir == null)
        {
            return false
        }
        val file = nodeDir.resolve(".toolkit/systemd.start_companions_first")
        if (!Files.isRegularFile(file))
        {
            return false
        }
        return runCatching { Files.readString(file).trim() == "1" }.getOrDefault(false)
    }

    fun readCompanions(nodeDir: Path?): List<String>
    {
        if (nodeDir == null)
        {
            return emptyList()
        }
        val file = nodeDir.resolve(".toolkit/systemd.companions")
        if (!Files.isRegularFile(file))
        {
            return emptyList()
        }
        return Files.readString(file).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    private fun companionBodyPath(nodeDir: Path, unit: String): Path
    {
        val safe = unit.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return nodeDir.resolve(".toolkit/systemd.companion.$safe.body")
    }

    private fun waitActive(unit: String, network: String, env: String): HostNodeStartResult
    {
        // Agave (and similar) often binds then panics within a few seconds — do not
        // promote to panel `sync` on a fleeting `active` + MainPID.
        val deadlineNs = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos()
        var lastHint = ""
        var stablePid: Long? = null
        var stableSinceNs = 0L
        val needStableNs = java.time.Duration.ofSeconds(4).toNanos()

        while (System.nanoTime() < deadlineNs)
        {
            val state = runSystemctl("is-active", unit).out.trim()
            when (state)
            {
                "active" ->
                {
                    val pid = mainPid(unit)
                    if (pid == null || !processAlive(pid))
                    {
                        stablePid = null
                        lastHint = "active but MainPID missing or dead"
                        Thread.sleep(400)
                        continue
                    }
                    if (stablePid != pid)
                    {
                        // New MainPID — restart/crash loop; reset settle window.
                        if (stablePid != null)
                        {
                            lastHint = "MainPID changed $stablePid → $pid (process restarting)"
                        }
                        stablePid = pid
                        stableSinceNs = System.nanoTime()
                        Thread.sleep(400)
                        continue
                    }
                    if (System.nanoTime() - stableSinceNs >= needStableNs)
                    {
                        val restarts = nRestarts(unit)
                        log.info(
                            "systemd unit={} started pid={} restarts={} ({} / {})",
                            unit,
                            pid,
                            restarts,
                            network,
                            env,
                        )
                        return HostNodeStartResult.Started(pid = pid)
                    }
                    Thread.sleep(400)
                }
                "activating", "reloading" ->
                {
                    stablePid = null
                    Thread.sleep(400)
                }
                else ->
                {
                    stablePid = null
                    val status = runSystemctl("status", "--no-pager", "-l", unit)
                    lastHint = status.out.take(700)
                    Thread.sleep(500)
                }
            }
        }

        val status = runSystemctl("status", "--no-pager", "-l", unit)
        val hint = lastHint.ifBlank { status.out.take(700) }
        val state = runSystemctl("is-active", unit).out.trim().ifBlank { "unknown" }
        return HostNodeStartResult.Failed(
            "unit $unit did not stay running (state=$state). " +
                "Process must remain alive before panel status becomes sync. $hint",
        )
    }

    private fun nRestarts(unit: String): Long
    {
        val show = runSystemctl("show", "-p", "NRestarts", "--value", unit)
        if (show.exit != 0)
        {
            return 0L
        }
        return show.out.trim().toLongOrNull() ?: 0L
    }

    private fun processAlive(pid: Long): Boolean =
        try
        {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        }
        catch (_: Exception)
        {
            false
        }

    private fun readLaunchRecord(nodeDir: Path): HostNodeLaunchRecord?
    {
        val file = nodeDir.resolve(".toolkit/launch.json")
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching { json.decodeFromString<HostNodeLaunchRecord>(Files.readString(file)) }.getOrNull()
    }

    private fun readRecordedUnitName(nodeDir: Path?): String?
    {
        val file = nodeDir
            ?.resolve(".toolkit/systemd.unit")
            ?.takeIf { Files.isRegularFile(it) }
            ?: return null
        return Files.readString(file).lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun sanitizeUnitPart(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-')

    /** Quote for systemd unit ExecStart word splitting (no shell). */
    private fun escapeSystemdArg(arg: String): String
    {
        if (arg.isEmpty()) return "\"\""
        if (arg.none { it.isWhitespace() || it == '"' || it == '\\' || it == '\'' })
        {
            return arg
        }
        return "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    private data class CmdResult(val exit: Int, val out: String)

    private fun runSystemctl(vararg args: String): CmdResult
    {
        val pb = ProcessBuilder(listOf("systemctl") + args.toList())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        return CmdResult(code, out)
    }

    private fun prepareEntry(
        nodeDir: Path,
        entry: String,
        extractGlob: String?,
        normalizeDir: String?,
    ): String?
    {
        val target = nodeDir.resolve(entry)
        if (Files.exists(target))
        {
            return null
        }
        val glob = extractGlob?.trim()?.takeIf { it.isNotEmpty() } ?: return "launch entry missing: $entry"
        val archive = findArchive(nodeDir, glob)
            ?: return "launch entry missing and no archive matching $glob"
        return try
        {
            extractTarGz(archive, nodeDir)
            normalizeDir?.trim()?.takeIf { it.isNotEmpty() }?.let { wanted ->
                normalizeExtractedDir(nodeDir, wanted)
            }
            if (!Files.exists(nodeDir.resolve(entry)))
            {
                "extract finished but entry still missing: $entry"
            }
            else
            {
                null
            }
        }
        catch (e: Exception)
        {
            e.message ?: "extract failed"
        }
    }

    private fun findArchive(nodeDir: Path, glob: String): Path?
    {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        return Files.list(nodeDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }
                .findFirst()
                .orElse(null)
        }
    }

    private fun extractTarGz(archive: Path, dest: Path)
    {
        val pb = ProcessBuilder("tar", "-xzf", archive.fileName.toString())
        pb.directory(dest.toFile())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val code = p.waitFor()
        if (code != 0)
        {
            val err = p.inputStream.bufferedReader().readText().take(400)
            error("tar exit $code: $err")
        }
    }

    private fun normalizeExtractedDir(nodeDir: Path, wanted: String)
    {
        val wantedPath = nodeDir.resolve(wanted)
        if (Files.isDirectory(wantedPath))
        {
            return
        }
        val top = Files.list(nodeDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                .filter {
                    val n = it.fileName.toString()
                    n.startsWith(wanted) || n.matches(Regex("$wanted-.*"))
                }
                .sorted(Comparator.comparing { it.fileName.toString() })
                .findFirst()
                .orElse(null)
        } ?: Files.list(nodeDir).use { stream ->
            stream
                .filter { Files.isDirectory(it) && !it.fileName.toString().startsWith(".") }
                .filter {
                    val n = it.fileName.toString()
                    n != "logs" && n != ".toolkit"
                }
                .findFirst()
                .orElse(null)
        }
        if (top != null && top.fileName.toString() != wanted)
        {
            Files.move(top, wantedPath)
        }
    }
}
