package rpcnode.toolkit.chains.arb.infrastructure.http

/**
 * Parses Nitro `--init.latest` / `--init.url` console progress from `logs/node.out`.
 *
 * Nitro rewrites one line with CR + CSI erase (`\u001b[2K`):
 * `transferred 5368709120 / 536870912000 bytes (1.00%) [458.86Mbps, 2h34m26s remaining]`
 *
 * Same pattern as Go `internal/l2evm` `nitroTransferRe`.
 */
object ArbNitroDownloadLog
{
    private val ansiRe = Regex("""\u001b\[[0-9;?]*[a-zA-Z]""")
    private val transferRe = Regex(
        """(?i)transferred\s+(\d+)\s*/\s*(\d+)\s*bytes\s*\(([0-9.]+)%\)\s*\[([^,\]]+),\s*([^\]]+?)\s*remaining\]""",
    )

    data class Progress(
        val pct: Double,
        val doneBytes: Long,
        val totalBytes: Long,
        val rate: String,
        val eta: String,
    )

    /** Last matching transfer line in [text]; null when none. */
    fun parse(text: String?): Progress?
    {
        if (text.isNullOrBlank())
        {
            return null
        }
        val plain = ansiRe.replace(text.replace('\r', '\n'), "")
        val m = transferRe.findAll(plain).lastOrNull() ?: return null
        val done = m.groupValues[1].toLongOrNull() ?: return null
        val total = m.groupValues[2].toLongOrNull() ?: return null
        val pct = m.groupValues[3].toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: return null
        if (pct <= 0.0 && done <= 0L)
        {
            return null
        }
        return Progress(
            pct = pct,
            doneBytes = done,
            totalBytes = total,
            rate = m.groupValues[4].trim(),
            eta = m.groupValues[5].trim(),
        )
    }
}
