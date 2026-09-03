package rpcnode.toolkit.agent.infrastructure.proc

import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Runs a long download outside the agent cgroup via `systemd-run --no-block`.
 * Agent restarts (watch / jar swap) no longer SIGKILL the downloader mid-transfer.
 *
 * Unit names always include the `.service` suffix — short names were failing
 * `systemctl stop` with "Unit … not loaded" while the transient unit was still active,
 * so wipe deleted the partial and aria2 immediately recreated it.
 */
object SystemdTransientDownload
{
    private val log = LoggerFactory.getLogger(SystemdTransientDownload::class.java)

    fun available(): Boolean =
        runningAsRoot() &&
            EnsureHostCurl.onPath("systemd-run") &&
            EnsureHostCurl.onPath("systemctl")

    fun unitName(label: String): String
    {
        val safe = label.lowercase()
            .replace(Regex("[^a-z0-9_-]+"), "-")
            .trim('-')
            .ifBlank { "job" }
            .take(120)
        return "rpcnode-snap-$safe.service"
    }

    /**
     * Starts [cmd] as a transient oneshot unit. No-op if the unit is already active
     * (attach / recover after agent restart).
     */
    fun start(unit: String, cmd: List<String>)
    {
        require(cmd.isNotEmpty()) { "empty download command" }
        val name = normalize(unit)
        if (isActive(name))
        {
            log.info("systemd unit {} already active — attaching", name)
            return
        }
        // Drop a leftover failed/inactive unit name so systemd-run can reuse it.
        runQuiet(listOf("systemctl", "reset-failed", name), allowFail = true)
        runQuiet(listOf("systemctl", "stop", name), allowFail = true)
        // systemd-run --unit= accepts name with or without .service; keep bare for --unit=.
        val bare = name.removeSuffix(".service")
        val launch = buildList {
            add("systemd-run")
            add("--unit=$bare")
            add("--no-block")
            // Keep the unit after exit so we can read ExecMainStatus; stop() cleans up.
            add("--property=Type=exec")
            add("--property=KillMode=mixed")
            add("--property=TimeoutStopSec=30")
            addAll(cmd)
        }
        log.info("starting detached download unit {} via systemd-run", name)
        val code = run(launch)
        if (code != 0)
        {
            error("systemd-run failed (exit $code) for unit $name")
        }
    }

    /**
     * Stops the unit and waits until it is gone. Uses SIGKILL if SIGTERM is ignored.
     * Also kills leftover aria2/curl processes writing into [destHint] when set.
     */
    fun stop(unit: String, destHint: String? = null)
    {
        val name = normalize(unit)
        runQuiet(listOf("systemctl", "stop", name), allowFail = true)
        if (isActive(name))
        {
            log.warn("unit {} still active after stop — sending SIGKILL", name)
            runQuiet(
                listOf("systemctl", "kill", "-s", "SIGKILL", "--kill-whom=all", name),
                allowFail = true,
            )
        }
        // Wait up to ~10s for the unit to leave active.
        repeat(20)
        {
            if (!isActive(name))
            {
                runQuiet(listOf("systemctl", "reset-failed", name), allowFail = true)
                destHint?.let { killOrphansForDest(it) }
                return
            }
            Thread.sleep(500)
        }
        log.warn("unit {} still active after SIGKILL wait", name)
        destHint?.let { killOrphansForDest(it) }
        runQuiet(listOf("systemctl", "reset-failed", name), allowFail = true)
    }

    fun isActive(unit: String): Boolean
    {
        val name = normalize(unit)
        val p = ProcessBuilder("systemctl", "is-active", "--quiet", name)
            .redirectErrorStream(true)
            .start()
        return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0
    }

    /** null while still running; otherwise the main process exit status (0 = ok). */
    fun exitStatus(unit: String): Int?
    {
        val name = normalize(unit)
        if (isActive(name))
        {
            return null
        }
        val out = runCapture(
            listOf(
                "systemctl",
                "show",
                name,
                "-p", "ExecMainStatus",
                "-p", "Result",
            ),
            allowFail = true,
        )
        val map = out.lineSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .associate { line ->
                val key = line.substringBefore('=')
                val value = line.substringAfter('=')
                key to value
            }
        val status = map["ExecMainStatus"]?.toIntOrNull()
        val result = map["Result"].orEmpty()
        if (status != null)
        {
            return status
        }
        return if (result.equals("success", ignoreCase = true)) 0 else 1
    }

    fun lastLogLine(unit: String): String
    {
        val out = runCapture(
            listOf(
                "journalctl",
                "-u", normalize(unit),
                "-n", "8",
                "-o", "cat",
                "--no-pager",
            ),
            allowFail = true,
        )
        return out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull().orEmpty()
    }

    private fun normalize(unit: String): String
    {
        val raw = unit.trim()
        if (raw.isEmpty())
        {
            error("empty systemd unit name")
        }
        return if (raw.endsWith(".service")) raw else "$raw.service"
    }

    /**
     * Last-resort: kill aria2c/curl still targeting this dest after the unit is gone.
     * Matches the `-d <toolkit>` / `-o` path we pass to aria2, or curl `-o` dest.
     */
    private fun killOrphansForDest(destDir: String)
    {
        val dest = destDir.trim()
        if (dest.isEmpty())
        {
            return
        }
        val toolkit = if (dest.endsWith(".toolkit")) dest else "$dest/.toolkit"
        val needle = toolkit
        try
        {
            val ps = ProcessBuilder("ps", "-eo", "pid=,args=")
                .redirectErrorStream(true)
                .start()
            val text = ps.inputStream.bufferedReader().readText()
            ps.waitFor(5, TimeUnit.SECONDS)
            for (line in text.lineSequence())
            {
                val trimmed = line.trim()
                if (trimmed.isEmpty())
                {
                    continue
                }
                if ("aria2c" !in trimmed && !trimmed.contains(" curl "))
                {
                    continue
                }
                if (needle !in trimmed && dest !in trimmed)
                {
                    continue
                }
                val pid = trimmed.substringBefore(' ').toLongOrNull() ?: continue
                log.warn("killing orphan downloader pid={} for {}", pid, needle)
                ProcessBuilder("kill", "-9", pid.toString())
                    .redirectErrorStream(true)
                    .start()
                    .waitFor(3, TimeUnit.SECONDS)
            }
        }
        catch (e: Exception)
        {
            log.warn("orphan kill failed: {}", e.message)
        }
    }

    private fun run(cmd: List<String>): Int
    {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor(60, TimeUnit.SECONDS)
        val code = if (ok) p.exitValue() else 1
        if (code != 0 && out.isNotBlank())
        {
            log.warn("{} → {}: {}", cmd.take(4).joinToString(" "), code, out.trim().take(400))
        }
        return code
    }

    private fun runQuiet(cmd: List<String>, allowFail: Boolean)
    {
        val code = run(cmd)
        if (code != 0 && !allowFail)
        {
            error("${cmd.joinToString(" ")} failed (exit $code)")
        }
    }

    private fun runCapture(cmd: List<String>, allowFail: Boolean = false): String
    {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        val ok = p.waitFor(15, TimeUnit.SECONDS) && p.exitValue() == 0
        if (!ok && !allowFail)
        {
            return out
        }
        return out
    }
}
