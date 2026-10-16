package rpcnode.toolkit.chains.cardano.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Mithril restore % from the heal script's state JSON or the mithril-client progress lines. */
object CardanoMithrilProgress
{
    private val json = Json { ignoreUnknownKeys = true }

    // mithril-client prints `Files: 120/450` style step bars and "[ 3/5 ]"-style step counters; a bare "NN%" is also accepted.
    private val files = Regex("""(?i)(?:files|immutable|ledger|ancillary)[^\n\d]{0,24}(\d+)\s*/\s*(\d+)""")
    private val pctBare = Regex("""(?i)(?:download|unpack|restor\w*|snapshot)[^\n%]{0,80}?(\d{1,3}(?:\.\d+)?)\s*%""")

    data class Progress(val pct: Double, val detail: String = "")

    fun read(nodeDir: String): Progress?
    {
        val root = nodeDir.trim()
        if (root.isEmpty()) return null
        val base = Path.of(root)
        val fromState = readState(base.resolve(".toolkit").resolve("snapshot-state.json"))
            ?: readState(base.resolve(".snapshot-state.json"))
        val fromLog = readLog(base.resolve("logs").resolve("cardano-snapshot.log"))
        return when
        {
            fromState == null -> fromLog
            fromLog == null -> fromState
            else -> if (fromState.pct >= fromLog.pct) fromState else fromLog
        }
    }

    fun parseLog(text: String): Progress?
    {
        var best: Progress? = null
        for (m in files.findAll(text))
        {
            val done = m.groupValues[1].toLongOrNull() ?: continue
            val total = m.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: continue
            val pct = (done.coerceAtMost(total).toDouble() / total * 100.0).coerceIn(0.0, 99.9)
            best = Progress(pct, "Mithril · $done / $total files")
        }
        if (best != null) return best
        for (m in pctBare.findAll(text))
        {
            val pct = m.groupValues[1].toDoubleOrNull()?.coerceIn(0.0, 99.9) ?: continue
            best = Progress(pct, "Mithril · ${"%.1f".format(Locale.ROOT, pct)}%")
        }
        return best
    }

    private fun readState(path: Path): Progress?
    {
        if (!Files.isRegularFile(path)) return null
        return try
        {
            val root = json.parseToJsonElement(Files.readString(path)).jsonObject
            val phase = root["phase"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (phase.equals("error", ignoreCase = true)) return null
            val pct = root["pct"]?.jsonPrimitive?.doubleOrNull?.coerceIn(0.0, 100.0) ?: return null
            Progress(pct.coerceAtMost(99.9), root["detail"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty())
        }
        catch (_: Exception)
        {
            null
        }
    }

    private fun readLog(path: Path): Progress?
    {
        if (!Files.isRegularFile(path)) return null
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
}
