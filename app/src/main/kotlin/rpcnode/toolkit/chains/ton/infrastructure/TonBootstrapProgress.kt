package rpcnode.toolkit.chains.ton.infrastructure

/**
 * Rough 0..100 progress of the MyTonCtrl bootstrap (`logs/bootstrap.log`), shown as the node's sync bar until the
 * TON HTTP API answers. The compile step dominates the time, so its ninja counter (`[123/4567] Building …`) carries
 * most of the range; the other stages are fixed waypoints.
 */
object TonBootstrapProgress
{
    private const val START = 3.0
    private const val BUILD_FROM = 10.0
    private const val BUILD_SPAN = 75.0
    private val reAnsi = Regex("""\u001b\[[0-9;?]*[a-zA-Z]""")
    private val reNinja = Regex("""(?m)^\[(\d+)/(\d+)] \S""")

    /** Waypoints, later ones win; checked against the current run of the log. */
    private val waypoints = listOf(
        "Checking system requirements" to 4.0,
        "TON does not exists, building" to 6.0,
        "clang-21 installation complete" to 8.0,
        "Source compilation" to BUILD_FROM,
        "Downloading config files" to 86.0,
        "TON software installation complete" to 88.0,
        "Installing MyTonCtrl" to 90.0,
        "Running mytoninstaller" to 94.0,
        "Mytonctrl installation completed" to 97.0,
        "install.sh exit=0" to 98.0,
    )

    /** Null when the log shows nothing of the bootstrap yet. */
    fun parse(logText: String): Double?
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
        val last = reNinja.findAll(text.substring(0, buildEnd))
            .mapNotNull { m ->
                val done = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val total = m.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                // `[2/5] Preparing …` stage markers are not build steps
                if (total < 50 || done > total) null else done.toDouble() / total
            }
            .lastOrNull()
        if (last != null)
        {
            pct = maxOf(pct, BUILD_FROM + BUILD_SPAN * last)
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
