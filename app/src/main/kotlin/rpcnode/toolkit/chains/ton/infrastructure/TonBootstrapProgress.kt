package rpcnode.toolkit.chains.ton.infrastructure

/**
 * Rough 0..100 progress of the MyTonCtrl bootstrap (`logs/bootstrap.log`), shown as the node's sync bar until the
 * TON HTTP API answers. Ranges follow where the time really goes on a dump install (measured: dump download
 * ~1 h 15 min, checksum ~5 min, extraction ~40 min, the rest minutes); the TON compile only runs when the binaries
 * are missing.
 */
object TonBootstrapProgress
{
    private const val START = 3.0
    private const val BUILD_FROM = 6.0
    private const val BUILD_SPAN = 34.0
    private const val DUMP_FROM = 45.0
    private const val DUMP_SPAN = 35.0
    private const val CHECKSUM_FROM = 80.0
    private const val CHECKSUM_SPAN = 5.0
    private const val EXTRACTING = 86.0
    private const val EXTRACTED = 95.0
    private val reAnsi = Regex("""\u001b\[[0-9;?]*[a-zA-Z]""")
    private val reNinja = Regex("""(?m)^\[(\d+)/(\d+)] \S""")
    /** aria2 status line: `[#dc25ec 68GiB/252GiB(27%) CN:8 DL:275MiB ETA:11m24s]` */
    private val reAria = Regex("""\[#[0-9a-f]+ [^\]/]+/[^\]/(]+\((\d{1,3})%\)""")
    /** `dumpDiskSize: 588949451776` — what the dump needs on disk once unpacked (printed before the extraction). */
    private val reDumpDiskSize = Regex("""dumpDiskSize:\s*(\d+)""")

    /** aria2 after the download: `… [Checksum:#034e29 417GiB/420GiB(99%)]` */
    private val reChecksum = Regex("""\[Checksum:#[0-9a-f]+ [^\]/]+/[^\]/(]+\((\d{1,3})%\)""")

    /** Waypoints, later ones win; checked against the current run of the log. */
    private val waypoints = listOf(
        "Checking system requirements" to 4.0,
        "TON does not exists, building" to 5.0,
        "clang-21 installation complete" to 5.5,
        "Source compilation" to BUILD_FROM,
        "Downloading config files" to 40.0,
        "TON software installation complete" to 41.0,
        "Installing MyTonCtrl" to 42.0,
        "Running mytoninstaller" to 44.0,
        "start download_dump" to DUMP_FROM,
        "Starting extraction" to EXTRACTING,
        "Dump extracted" to EXTRACTED,
        "Mytonctrl installation completed" to 97.0,
        "install.sh exit=0" to 98.0,
    )

    /**
     * Null when the log shows nothing of the bootstrap yet.
     *
     * The extraction (`plzip | tar`, ~40 min for a 400+ GiB dump) prints nothing, so its share is read from the disk:
     * [extractedBytes] (size of the unpacked DB so far, asked only while extracting) over the `dumpDiskSize` the log
     * announced. Without it the bar waits at the start of the extraction range.
     */
    fun parse(logText: String, extractedBytes: () -> Long? = { null }): Double?
    {
        if (logText.isBlank())
        {
            return null
        }
        val text = currentRun(reAnsi.replace(logText, ""))
        var pct = START
        for ((marker, value) in waypoints)
        {
            if (text.contains(marker) && value > pct)
            {
                pct = value
            }
        }
        // The build counter only counts while the compile step is the current one (before "Downloading config files").
        val buildEnd = text.indexOf("Downloading config files").takeIf { it >= 0 } ?: text.length
        val build = reNinja.findAll(text.substring(0, buildEnd))
            .mapNotNull { m ->
                val done = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val total = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                // `[2/5] Preparing …` stage markers are not build steps
                if (total < 50 || done > total) null else done.toDouble() / total
            }
            .lastOrNull()
        if (build != null)
        {
            pct = maxOf(pct, BUILD_FROM + BUILD_SPAN * build)
        }
        // Dump: aria2 download, then its checksum pass — both only after download_dump started.
        val dumpStart = text.indexOf("start download_dump")
        if (dumpStart >= 0)
        {
            val dump = text.substring(dumpStart)
            reAria.findAll(dump).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                pct = maxOf(pct, DUMP_FROM + DUMP_SPAN * (it.coerceIn(0.0, 100.0) / 100.0))
            }
            reChecksum.findAll(dump).lastOrNull()?.groupValues?.get(1)?.toDoubleOrNull()?.let {
                pct = maxOf(pct, CHECKSUM_FROM + CHECKSUM_SPAN * (it.coerceIn(0.0, 100.0) / 100.0))
            }
        }
        if (text.contains("Starting extraction") && !text.contains("Dump extracted"))
        {
            val total = reDumpDiskSize.findAll(text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
            val done = if (total != null && total > 0) extractedBytes() else null
            if (total != null && done != null)
            {
                val fraction = (done.toDouble() / total).coerceIn(0.0, 1.0)
                // never reaches EXTRACTED itself: "Dump extracted" in the log does that
                pct = maxOf(pct, EXTRACTING + (EXTRACTED - 1.0 - EXTRACTING) * fraction)
            }
        }
        return pct.coerceIn(0.0, 99.0)
    }

    /** The log keeps every retry; only the last run counts. */
    private fun currentRun(text: String): String
    {
        val start = text.lastIndexOf("Checking system requirements")
        return if (start >= 0) text.substring(start) else text
    }
}
