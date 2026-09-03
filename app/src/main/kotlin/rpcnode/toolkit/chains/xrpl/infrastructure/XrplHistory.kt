package rpcnode.toolkit.chains.xrpl.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/**
 * Install option group `xrpl_history` — how many ledgers xrpld keeps.
 * Default weeks (300_000). Matches admin XrplHistoryPicker / Go ParseHistoryMode.
 */
data class XrplHistoryPolicy(
    val mode: String,
    val ledgers: Int,
)

object XrplHistory
{
    const val GROUP = "xrpl_history"

    const val STOCK = "stock"
    const val DAY = "day"
    const val WEEKS = "weeks"
    const val FULL = "full"

    val DEFAULT = XrplHistoryPolicy(mode = WEEKS, ledgers = 300_000)

    fun parse(raw: String?): XrplHistoryPolicy
    {
        val s = raw?.trim()?.lowercase().orEmpty()
        return when (s)
        {
            STOCK, "default", "2000" -> XrplHistoryPolicy(STOCK, 2_000)
            DAY, "1d", "25000" -> XrplHistoryPolicy(DAY, 25_000)
            WEEKS, "14d", "2w", "300000" -> XrplHistoryPolicy(WEEKS, 300_000)
            FULL -> XrplHistoryPolicy(FULL, 0)
            "" -> DEFAULT
            else ->
            {
                val n = s.toIntOrNull()
                if (n != null && n >= 256)
                {
                    XrplHistoryPolicy("custom", n)
                }
                else
                {
                    DEFAULT
                }
            }
        }
    }

    fun fromJson(installOptionsJson: String?): XrplHistoryPolicy
    {
        val raw = installOptionsJson?.trim().orEmpty()
        if (raw.isEmpty())
        {
            return DEFAULT
        }
        val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return DEFAULT
        return parse(root[GROUP]?.jsonPrimitive?.contentOrNull)
    }

    /**
     * Honest sync bar. [target] from policy ledgers (0 = full / genesis proof).
     * 100 only when live tip AND history window OK.
     */
    fun verificationPct(
        live: Boolean,
        historyOk: Boolean,
        lo: Long,
        hi: Long,
        seq: Long,
        genesis: Long,
        target: Int,
    ): Double
    {
        if (target > 0)
        {
            if (live && historyOk)
            {
                return 100.0
            }
            if (!live)
            {
                return windowPct(false, false, lo, hi, seq, genesis)
            }
            if (lo <= 0 || hi <= 0)
            {
                return 0.0
            }
            val have = hi - lo + 1
            var pct = have.toDouble() / target.toDouble() * 100.0
            pct = kotlin.math.round(pct * 1000.0) / 1000.0
            if (pct < 0.001 && have > 0)
            {
                return 0.001
            }
            if (pct >= 100.0 && !historyOk)
            {
                return 99.9
            }
            return pct
        }
        return windowPct(live, historyOk, lo, hi, seq, genesis)
    }

    fun historyOk(env: String, lo: Long, hi: Long, seq: Long, pol: XrplHistoryPolicy): Boolean
    {
        if (pol.mode == FULL || pol.ledgers <= 0)
        {
            val genesis = XrplClusters.genesisLedger(env)
            if (lo <= 0 || hi <= 0 || seq <= 0)
            {
                return false
            }
            if (hi + 16 < seq)
            {
                return false
            }
            return lo <= genesis + 16
        }
        if (lo <= 0 || hi <= 0 || seq <= 0)
        {
            return false
        }
        if (hi + 16 < seq)
        {
            return false
        }
        return hi - lo + 1 >= pol.ledgers.toLong()
    }

    fun tipLive(serverState: String): Boolean
    {
        return when (serverState.trim().lowercase())
        {
            "full", "proposing", "validating" -> true
            else -> false
        }
    }

    /** Parse `complete_ledgers` — `"106-108"`, `"empty"`, or `"1-100,200-300"`. */
    fun parseCompleteLedgers(raw: String?): Pair<Long, Long>
    {
        val s = raw?.trim()?.lowercase().orEmpty()
        if (s.isEmpty() || s == "empty" || s == "none")
        {
            return 0L to 0L
        }
        var lo = 0L
        var hi = 0L
        for (part in s.split(','))
        {
            val p = part.trim()
            if (p.isEmpty()) continue
            val dash = p.indexOf('-')
            val a: Long
            val b: Long
            if (dash > 0)
            {
                a = p.substring(0, dash).trim().toLongOrNull() ?: 0L
                b = p.substring(dash + 1).trim().toLongOrNull() ?: 0L
            }
            else
            {
                val n = p.toLongOrNull() ?: 0L
                a = n
                b = n
            }
            if (a > 0 && (lo == 0L || a < lo))
            {
                lo = a
            }
            if (b > hi)
            {
                hi = b
            }
            if (a > hi)
            {
                hi = a
            }
        }
        return lo to hi
    }

    private fun windowPct(
        live: Boolean,
        historyOk: Boolean,
        lo: Long,
        hi: Long,
        seq: Long,
        genesis: Long,
    ): Double
    {
        if (live && historyOk)
        {
            return 100.0
        }
        if (lo <= 0 || hi <= 0 || seq <= 0 || seq <= genesis)
        {
            return 0.0
        }
        val span = (seq - genesis).coerceAtLeast(1)
        val have = (hi - lo + 1).coerceAtLeast(0)
        var pct = have.toDouble() / span.toDouble() * 100.0
        pct = kotlin.math.round(pct * 1000.0) / 1000.0
        if (pct < 0.001 && have > 0)
        {
            return 0.001
        }
        if (pct >= 100.0 && !historyOk)
        {
            return 99.9
        }
        return pct.coerceIn(0.0, 99.9)
    }
}
