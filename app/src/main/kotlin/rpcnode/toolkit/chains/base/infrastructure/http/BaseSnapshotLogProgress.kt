package rpcnode.toolkit.chains.base.infrastructure.http

import rpcnode.toolkit.chains.base.infrastructure.BaseClusters

/**
 * Parses `base-reth-node download` / heal-script log text into wizard progress.
 * Ported from Go `internal/networks/base/snapshot.go` ParseSnapshotProgress.
 */
object BaseSnapshotLogProgress
{
    const val PHASE_DOWNLOAD = "download"
    const val PHASE_EXTRACT = "extract"
    const val PHASE_VERIFY = "verify"
    const val PHASE_MANIFEST = "manifest"
    const val PHASE_MIGRATE = "migrate"
    const val PHASE_DONE = "done"

    private const val BAND_DOWNLOAD = 90.0
    private const val BAND_EXTRACT = 8.0
    private const val BAND_VERIFY = 1.5

    private val reAnsi = Regex("""\u001b\[[0-9;?]*[a-zA-Z]""")
    private val rePct = Regex("""(?:progress=|\()\s*([0-9]{1,3}(?:\.[0-9]+)?)\s*%""")
    private val rePctBare = Regex("""([0-9]{1,3}(?:\.[0-9]+)?)\s*%""")
    private val reFiles = Regex(
        """(?i)(?:archives=|files=)?\b([0-9]+)\s*/\s*([0-9]+)\b\s*(?:files|archives|segments|parts)?""",
    )
    private val reBytes = Regex(
        """(?i)([0-9][0-9.,]*)\s*([KMGTP]i?B)\s*/\s*([0-9][0-9.,]*)\s*([KMGTP]i?B)""",
    )
    private val reManifest = Regex("""(?i)block[= ]([0-9]{4,})""")
    private val reEta = Regex("""(?i)eta[ =:]+([0-9hmsd:]+)""")

    data class Progress(
        val phase: String = "",
        val pct: Double? = null,
        val detail: String = "",
        val block: Long = 0,
    )

    fun parse(logText: String, flavor: String): Progress?
    {
        val cur = currentRunText(logText)
        if (cur.isBlank())
        {
            return null
        }
        val flavorId = BaseClusters.normalizeSnapshotFlavor(flavor)
        val head = "Base V2 snapshot · $flavorId"
        var block = 0L
        reManifest.findAll(cur).lastOrNull()?.groupValues?.getOrNull(1)?.toLongOrNull()?.let {
            block = it
        }
        if (runFinished(cur))
        {
            return Progress(phase = PHASE_DONE, pct = 100.0, detail = "$head · ready", block = block)
        }
        val (line, phase) = lastPhaseLine(cur)
        if (line.isBlank())
        {
            return if (block > 0)
            {
                Progress(
                    phase = PHASE_MANIFEST,
                    pct = null,
                    detail = "$head · manifest block $block",
                    block = block,
                )
            }
            else
            {
                null
            }
        }
        var archives = 0
        var totalArchives = 0
        reFiles.find(line)?.let { m ->
            archives = m.groupValues[1].toIntOrNull() ?: 0
            totalArchives = m.groupValues[2].toIntOrNull() ?: 0
        }
        var bytes = ""
        var totalBytes = ""
        var byteRatio = 0.0
        reBytes.find(line)?.let { m ->
            bytes = tidySize(m.groupValues[1], m.groupValues[2])
            totalBytes = tidySize(m.groupValues[3], m.groupValues[4])
            val done = sizeBytes(m.groupValues[1], m.groupValues[2])
            val total = sizeBytes(m.groupValues[3], m.groupValues[4])
            if (total > 0 && done >= 0 && done <= total)
            {
                byteRatio = done / total * 100.0
            }
        }
        val eta = reEta.find(line)?.groupValues?.getOrNull(1).orEmpty()
        val linePct = pctOnLine(line)
        val pct = when
        {
            linePct >= 0 -> bandedPct(phase, linePct)
            byteRatio > 0 -> bandedPct(phase, byteRatio)
            totalArchives > 0 && archives <= totalArchives ->
                bandedPct(phase, archives.toDouble() / totalArchives.toDouble() * 100.0)
            else -> null
        }
        val detail = buildDetail(head, phase, block, archives, totalArchives, bytes, totalBytes, eta)
        return Progress(phase = phase, pct = pct, detail = detail, block = block)
    }

    fun currentRunText(text: String): String
    {
        val plain = reAnsi.replace(text.replace('\r', '\n'), "")
        if (plain.isBlank())
        {
            return ""
        }
        val low = plain.lowercase()
        var cut = -1
        for (n in listOf("snapshot_diag begin", "start official snapshot"))
        {
            val i = low.lastIndexOf(n)
            if (i > cut)
            {
                cut = i
            }
        }
        return if (cut >= 0) plain.substring(cut) else plain
    }

    fun runFinished(text: String): Boolean
    {
        val low = currentRunText(text).lowercase()
        return low.contains("snapshot_diag done base ") ||
            low.contains("\ndone base ") ||
            low.trimStart().startsWith("done base ")
    }

    private fun buildDetail(
        head: String,
        phase: String,
        block: Long,
        archives: Int,
        totalArchives: Int,
        bytes: String,
        totalBytes: String,
        eta: String,
    ): String
    {
        when (phase)
        {
            PHASE_DONE -> return "$head · ready"
            PHASE_MIGRATE -> return "$head · V1 → V2 storage migration"
            PHASE_MANIFEST ->
                return if (block > 0) "$head · manifest block $block" else "$head · resolving manifest"
        }
        val verb = when (phase)
        {
            PHASE_DOWNLOAD -> "downloading"
            PHASE_EXTRACT -> "extracting"
            PHASE_VERIFY -> "verifying"
            else -> "working"
        }
        var out = "$head · $verb"
        if (totalArchives > 0)
        {
            out += " $archives/$totalArchives files"
        }
        if (bytes.isNotBlank() && totalBytes.isNotBlank())
        {
            out += " · $bytes / $totalBytes"
        }
        if (eta.isNotBlank())
        {
            out += " · eta $eta"
        }
        return out
    }

    private fun lastPhaseLine(text: String): Pair<String, String>
    {
        var bareLine = ""
        var barePhase = ""
        for (ln in text.lineSequence().toList().asReversed())
        {
            val ph = phaseOfLine(ln)
            if (ph.isEmpty() || ph == PHASE_MANIFEST)
            {
                continue
            }
            val hasNumbers = pctOnLine(ln) >= 0 || reBytes.containsMatchIn(ln) || reFiles.containsMatchIn(ln)
            if (hasNumbers)
            {
                return ln to ph
            }
            if (bareLine.isEmpty())
            {
                bareLine = ln
                barePhase = ph
            }
        }
        return bareLine to barePhase
    }

    private fun phaseOfLine(line: String): String
    {
        val low = line.lowercase()
        return when
        {
            "migrat" in low -> PHASE_MIGRATE
            "verif" in low -> PHASE_VERIFY
            "extract" in low || "decompress" in low || "unpack" in low -> PHASE_EXTRACT
            "download" in low || "fetch" in low -> PHASE_DOWNLOAD
            "manifest" in low -> PHASE_MANIFEST
            else -> ""
        }
    }

    private fun pctOnLine(line: String): Double
    {
        val m = rePct.find(line) ?: rePctBare.find(line) ?: return -1.0
        val v = m.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return -1.0
        return if (v in 0.0..100.0) v else -1.0
    }

    private fun bandedPct(phase: String, phasePct: Double): Double =
        when (phase)
        {
            PHASE_EXTRACT -> BAND_DOWNLOAD + phasePct * BAND_EXTRACT / 100.0
            PHASE_VERIFY -> BAND_DOWNLOAD + BAND_EXTRACT + phasePct * BAND_VERIFY / 100.0
            else -> phasePct * BAND_DOWNLOAD / 100.0
        }

    private fun tidySize(num: String, unit: String): String =
        "${num.trim().trimEnd(',')} ${unit.trim()}"

    private fun sizeBytes(num: String, unit: String): Double
    {
        val v = num.trim().replace(",", "").toDoubleOrNull() ?: return -1.0
        val mul = when (unit.trim().uppercase())
        {
            "B" -> 1.0
            "KB" -> 1e3
            "MB" -> 1e6
            "GB" -> 1e9
            "TB" -> 1e12
            "PB" -> 1e15
            "KIB" -> (1L shl 10).toDouble()
            "MIB" -> (1L shl 20).toDouble()
            "GIB" -> (1L shl 30).toDouble()
            "TIB" -> (1L shl 40).toDouble()
            "PIB" -> (1L shl 50).toDouble()
            else -> return -1.0
        }
        return v * mul
    }
}
