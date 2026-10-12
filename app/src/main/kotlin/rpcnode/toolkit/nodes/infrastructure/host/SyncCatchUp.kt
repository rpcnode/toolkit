package rpcnode.toolkit.nodes.infrastructure.host

import java.nio.file.Files
import java.nio.file.Path

/**
 * Honest catch-up bar for a node that is far behind: share of the *first seen* lag already closed. Absolute block
 * ratios (116.8 M / 126.8 M = 92 %) say nothing when the node is weeks behind. The first lag is kept in
 * `{nodeDir}/.toolkit/<name>` so a restart does not send the bar back to 0.
 */
object SyncCatchUp
{
    /** 0..99.9; 0 when there is no usable start lag. Never 100: "done" is the node reporting synced. */
    fun pct(startLag: Double, lag: Double): Double
    {
        if (startLag <= 0.0)
        {
            return 0.0
        }
        return ((1.0 - lag / startLag) * 100.0).coerceIn(0.0, 99.9)
    }

    /** Lag the bar is measured from: the stored one, raised when the lag grew since (chain outran the node). */
    fun startLag(nodeDir: String, name: String, lag: Double): Double
    {
        val file = Path.of(nodeDir.trim()).resolve(".toolkit").resolve(name)
        val stored = runCatching { Files.readString(file).trim().toDouble() }.getOrNull()
        if (stored != null && stored >= lag)
        {
            return stored
        }
        runCatching {
            Files.createDirectories(file.parent)
            Files.writeString(file, "$lag\n")
        }
        return lag
    }
}
