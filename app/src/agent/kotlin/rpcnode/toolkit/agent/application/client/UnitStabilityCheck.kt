package rpcnode.toolkit.agent.application.client

/** One look at a systemd unit (`systemctl show`). */
data class UnitSample(
    /** ActiveState: active | activating | deactivating | inactive | failed */
    val active: String,
    /** SubState, e.g. running | auto-restart | dead */
    val sub: String = "",
    val mainPid: Long = 0,
    /** NRestarts — how often systemd restarted the unit (Restart=always/on-failure). */
    val restarts: Long = 0,
)

/**
 * Decides whether a freshly started node unit is really up after a client update.
 *
 * `is-active` alone is not enough: a crashing client under `Restart=always` flaps
 * active → activating (auto-restart) → active, and a single look at the end of the trial can
 * land on "active". So the unit has to keep the same MainPID, with no new restarts, for the
 * whole trial window.
 */
class UnitStabilityCheck(
    private val probe: (unit: String) -> UnitSample,
    private val trialSeconds: Int = 60,
    private val sleepSeconds: (Int) -> Unit = { Thread.sleep(it * 1_000L) },
)
{
    /** Null when stable, otherwise the reason (shown to the operator). */
    fun verify(unit: String): String?
    {
        var pid: Long? = null
        var restarts = 0L
        repeat(trialSeconds) {
            val s = probe(unit)
            val running = s.active == "active" && s.sub != "auto-restart"
            if (running)
            {
                if (pid == null)
                {
                    pid = s.mainPid
                    restarts = s.restarts
                }
                else if (s.mainPid != pid || s.restarts > restarts)
                {
                    return "process was restarted by systemd (crash loop): pid $pid → ${s.mainPid}, " +
                        "${s.restarts} restart(s)"
                }
            }
            else if (pid != null)
            {
                return if (s.sub == "auto-restart" || s.active == "activating")
                {
                    "process crashed and systemd is restarting it (crash loop)"
                }
                else
                {
                    "unit went ${s.active.ifBlank { "unknown" }} after it had started"
                }
            }
            sleepSeconds(1)
        }
        val last = probe(unit)
        return if (pid != null && last.active == "active" && last.sub != "auto-restart" && last.mainPid == pid)
        {
            null
        }
        else
        {
            "unit did not stay active for ${trialSeconds}s (state: ${last.active}/${last.sub})"
        }
    }
}

/** Real probe: `systemctl show`. */
fun systemctlUnitSample(unit: String): UnitSample =
    runCatching {
        val proc = ProcessBuilder("systemctl", "show", "-p", "ActiveState", "-p", "SubState", "-p", "MainPID", "-p", "NRestarts", unit)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        val kv = out.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1).trim()
        }.toMap()
        UnitSample(
            active = kv["ActiveState"].orEmpty(),
            sub = kv["SubState"].orEmpty(),
            mainPid = kv["MainPID"]?.toLongOrNull() ?: 0,
            restarts = kv["NRestarts"]?.toLongOrNull() ?: 0,
        )
    }.getOrDefault(UnitSample(active = ""))
