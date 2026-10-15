package rpcnode.toolkit.chains.sui.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.text.Charsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Formal snapshot % from state JSON or sui-tool log lines. */
object SuiFormalSnapshotProgress
{
    private val json = Json { ignoreUnknownKeys = true }
    // indicatif bars, e.g. "[00:01:02] 40 out of 3000 .obj files done (Download speed: 90 MiB/s)"
    // or "[00:01:02] 12/900 (checkpoints synced per sec: 3.1)".
    private val bar = Regex("""(\d+)\s*(?:out of|/)\s*(\d+)\s*([^(\r\n]*)(?:\(([^)\r\n]*)\))?""")
    private val pctBare = Regex(
        """(?i)(?:download|restor\w*|snapshot)[^\n%]{0,80}\s(\d{1,3}(?:\.\d+)?)\s*%""",
    )

    /** sui-tool phases with their share of the whole run (sum = 100). */
    private enum class Phase(val title: String, val weight: Double)
    {
        SUMMARIES("Checkpoint summaries", 10.0),
        REF("Missing .ref files", 5.0),
        CHECKSUM("Checksumming .ref files", 5.0),
        OBJ("Downloading .obj files", 65.0),
        ACCUMULATE("Accumulating state", 10.0),
        VERIFY("Verifying checkpoints", 5.0),
    }

    private fun phaseOf(label: String, msg: String): Phase? = when
    {
        label.contains(".obj") -> Phase.OBJ
        label.contains("checksummed") -> Phase.CHECKSUM
        label.contains("accumulated") -> Phase.ACCUMULATE
        label.contains(".ref") -> Phase.REF
        label.isBlank() && msg.contains("verified") -> Phase.VERIFY
        label.isBlank() -> Phase.SUMMARIES
        label.contains("files done") -> Phase.OBJ
        else -> null
    }

    data class Progress(val pct: Double, val detail: String = "")

    fun read(nodeDir: String): Progress?
    {
        val root = nodeDir.trim()
        if (root.isEmpty())
        {
            return null
        }
        val base = Path.of(root)
        val fromState = readState(base.resolve(".snapshot-state.json"))
            ?: readState(base.resolve(".toolkit").resolve("snapshot-state.json"))
        val fromLog = readLog(
            listOf(
                base.resolve("logs").resolve("sui-snapshot-progress.txt"),
                base.resolve("logs").resolve("sui-snapshot.log"),
                Path.of("/var/log/sui", "snapshot.log"),
            ),
        )
        return pickBetter(fromState, fromLog)
    }

    fun parseLog(text: String): Progress?
    {
        val latest = linkedMapOf<Phase, Triple<Long, Long, String>>()
        for (match in bar.findAll(text))
        {
            val done = match.groupValues[1].toLongOrNull() ?: continue
            val total = match.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: continue
            val msg = match.groupValues[4].trim()
            val phase = phaseOf(match.groupValues[3].trim().lowercase(), msg.lowercase()) ?: continue
            latest.remove(phase)
            latest[phase] = Triple(done.coerceAtMost(total), total, msg)
        }
        if (latest.isNotEmpty())
        {
            val pct = latest.entries.sumOf { (phase, v) -> phase.weight * v.first / v.second }
            // Most advanced unfinished bar; else the last one updated.
            val (phase, v) = latest.entries
                .filter { it.value.first < it.value.second }
                .maxByOrNull { it.key.ordinal }
                ?.toPair()
                ?: latest.entries.last().toPair()
            val speed = v.third.takeIf { it.contains("MiB/s") }
                ?.let { Regex("""(\d+(?:\.\d+)?)\s*MiB/s""").find(it)?.groupValues?.get(1)?.toDoubleOrNull() }
                ?.let { " · ${"%.1f".format(Locale.ROOT, it)} MiB/s" }
                .orEmpty()
            return Progress(
                pct = pct.coerceIn(0.0, 99.9),
                detail = "${phase.title} · ${v.first} / ${v.second}$speed",
            )
        }
        var best: Progress? = null
        for (match in pctBare.findAll(text))
        {
            val pct = match.groupValues[1].toDoubleOrNull()?.coerceIn(0.0, 99.9) ?: continue
            val p = Progress(pct = pct, detail = "Formal snapshot · ${"%.1f".format(pct)}%")
            if (best == null || p.pct >= best.pct)
            {
                best = p
            }
        }
        return best
    }

    private fun readState(path: Path): Progress?
    {
        if (!Files.isRegularFile(path))
        {
            return null
        }
        return try
        {
            val root = json.parseToJsonElement(Files.readString(path)).jsonObject
            val phase = root["phase"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (phase.equals("error", ignoreCase = true))
            {
                return null
            }
            val pct = root["pct"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 100.0) ?: return null
            val detail = root["detail"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            Progress(pct = pct.coerceAtMost(99.9), detail = detail)
        }
        catch (_: Exception)
        {
            null
        }
    }

    private fun readLog(candidates: List<Path>): Progress?
    {
        for (path in candidates)
        {
            if (!Files.isRegularFile(path))
            {
                continue
            }
            try
            {
                val bytes = Files.readAllBytes(path)
                val take = minOf(bytes.size, 512 * 1024)
                parseLog(String(bytes, bytes.size - take, take, Charsets.UTF_8))?.let { return it }
            }
            catch (_: Exception)
            {
            }
        }
        return null
    }

    private fun pickBetter(a: Progress?, b: Progress?): Progress?
    {
        if (a == null) return b
        if (b == null) return a
        return if (a.pct >= b.pct) a else b
    }
}
