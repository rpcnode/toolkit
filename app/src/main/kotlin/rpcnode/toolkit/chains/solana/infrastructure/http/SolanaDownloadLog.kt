package rpcnode.toolkit.chains.solana.infrastructure.http

/**
 * Parse Agave `solana_file_download` / snapshot lines from validator.log
 * (same patterns as admin `parseSolanaDownloadPctFromText`).
 *
 * Examples:
 * - `downloaded 9017562 bytes 0.2% 58070.4 bytes/s`
 * - `snapshot download 28.0%`
 */
object SolanaDownloadLog
{
    private val pctRe =
        Regex(
            """downloaded\s+\d+\s+bytes\s+([0-9.]+)%|snapshot download\s+([0-9.]+)%""",
            RegexOption.IGNORE_CASE,
        )

    private val rateRe =
        Regex(
            """downloaded\s+(\d+)\s+bytes\s+[0-9.]+%\s+([0-9.]+)\s+bytes/s""",
            RegexOption.IGNORE_CASE,
        )

    data class Progress(
        val pct: Double,
        val bytes: Long? = null,
        val bytesPerSec: Double? = null,
    )

    /** Last matching progress in [text]; null when none. */
    fun parse(text: String?): Progress?
    {
        if (text.isNullOrBlank())
        {
            return null
        }
        var lastPct: Double? = null
        for (m in pctRe.findAll(text))
        {
            val raw = m.groupValues[1].ifBlank { m.groupValues[2] }
            val n = raw.toDoubleOrNull() ?: continue
            if (n > 0)
            {
                lastPct = n.coerceIn(0.0, 100.0)
            }
        }
        val pct = lastPct ?: return null
        val rate = rateRe.findAll(text).lastOrNull()
        return Progress(
            pct = pct,
            bytes = rate?.groupValues?.getOrNull(1)?.toLongOrNull(),
            bytesPerSec = rate?.groupValues?.getOrNull(2)?.toDoubleOrNull(),
        )
    }
}
