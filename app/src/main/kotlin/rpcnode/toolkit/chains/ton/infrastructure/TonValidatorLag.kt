package rpcnode.toolkit.chains.ton.infrastructure

/**
 * Catch-up after the MyTonCtrl dump: until the liteserver is synced, TON HTTP API answers 503, so the only live signal
 * is the validator's own STATUS line (`last_masterchain_block_ago=14.0h …`).
 */
object TonValidatorLag
{
    private val re = Regex("""last_masterchain_block_ago=([0-9]+(?:\.[0-9]+)?)([smhd])""")

    /** Lag of the newest STATUS line in seconds, null when the text has none. */
    fun lastLagSeconds(logText: String): Double?
    {
        val m = re.findAll(logText).lastOrNull() ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return n * when (m.groupValues[2])
        {
            "s" -> 1.0
            "m" -> 60.0
            "h" -> 3600.0
            else -> 86400.0
        }
    }

    /**
     * Share of the gap closed since catch-up began ([startLagSec] = first lag seen), 0..99.
     * Never 100: done is when the HTTP API answers with a seqno.
     */
    fun catchUpPct(startLagSec: Double, nowLagSec: Double): Double
    {
        if (startLagSec <= 0.0)
        {
            return 0.0
        }
        return ((1.0 - nowLagSec / startLagSec) * 100.0).coerceIn(0.0, 99.0)
    }
}
