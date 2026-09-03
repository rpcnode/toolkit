package rpcnode.toolkit.chains.sui.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
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
    private val filesDone = Regex("""(?i)(\d+)\s+out of\s+(\d+)\s+files?\s+done""")
    private val pctBare = Regex(
        """(?i)(?:download|restor\w*|snapshot)[^\n%]{0,80}\s(\d{1,3}(?:\.\d+)?)\s*%""",
    )

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
                base.resolve("logs").resolve("sui-snapshot.log"),
                Path.of("/var/log/sui", "snapshot.log"),
            ),
        )
        return pickBetter(fromState, fromLog)
    }

    fun parseLog(text: String): Progress?
    {
        var best: Progress? = null
        for (match in filesDone.findAll(text))
        {
            val done = match.groupValues[1].toDoubleOrNull() ?: continue
            val total = match.groupValues[2].toDoubleOrNull()?.takeIf { it > 0 } ?: continue
            val pct = ((done / total) * 100.0).coerceIn(0.0, 99.9)
            val p = Progress(pct = pct, detail = "${done.toLong()} / ${total.toLong()} files")
            if (best == null || p.pct >= best.pct)
            {
                best = p
            }
        }
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
        val path = candidates.firstOrNull { Files.isRegularFile(it) } ?: return null
        return try
        {
            val bytes = Files.readAllBytes(path)
            val take = minOf(bytes.size, 512 * 1024)
            parseLog(String(bytes, bytes.size - take, take, Charsets.UTF_8))
        }
        catch (_: Exception)
        {
            null
        }
    }

    private fun pickBetter(a: Progress?, b: Progress?): Progress?
    {
        if (a == null) return b
        if (b == null) return a
        return if (a.pct >= b.pct) a else b
    }
}
