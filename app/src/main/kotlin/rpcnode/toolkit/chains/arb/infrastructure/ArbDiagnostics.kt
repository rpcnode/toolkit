package rpcnode.toolkit.chains.arb.infrastructure

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticResult

/** Nitro checks declared in `chains/{arb,robinhood}/network.yml` → `diagnostics` (`arb_l1`). */
object ArbDiagnostics
{
    private const val TAIL_BYTES = 512 * 1024

    /** Only the last lines count: an old burst of errors must not outlive a fixed RPC. */
    private const val RECENT_LINES = 300

    private val inboxError = Regex("""error reading inbox\s+err="(.*)"""")

    /**
     * Nitro cannot advance without the batches it reads from the parent chain RPC (`eth_getLogs`). The default public
     * L1 endpoint prunes old logs and rate-limits (429), so the node loops on `error reading inbox` and the bar never moves.
     */
    fun parentChain(check: DiagnosticRequest, log: () -> String? = { tailLog(check.params["node_dir"]) }): DiagnosticResult
    {
        val text = log()
            ?: return DiagnosticResult(check.id, "unknown", "Node log not found", "No logs/node.out on the host yet.")
        return classify(check.id, text)
    }

    fun classify(id: String, log: String): DiagnosticResult
    {
        val recent = log.lineSequence().toList().takeLast(RECENT_LINES)
        val errors = recent.mapNotNull { inboxError.find(it)?.groupValues?.get(1) }
        if (errors.isEmpty())
        {
            return DiagnosticResult(id, "ok", "Parent chain RPC answers", "No «error reading inbox» in the recent log.")
        }
        val limited = errors.count { it.contains("429") || it.contains("Rate limit", ignoreCase = true) }
        val pruned = errors.count { it.contains("pruned history", ignoreCase = true) }
        val causes = buildList {
            if (pruned > 0) add("$pruned × «pruned history unavailable» (the L1 node has dropped these logs)")
            if (limited > 0) add("$limited × 429 rate limit")
            if (isEmpty()) add("${errors.size} read errors, last: ${errors.last().take(160)}")
        }
        return DiagnosticResult(
            id = id,
            level = "error",
            title = "Parent chain RPC cannot serve the inbox — sync is stalled",
            detail = "Nitro reads the batches posted on L1 with eth_getLogs, and the configured L1 endpoint fails: " +
                causes.joinToString("; ") + ". Until it answers, the node stays in sync and the bar does not move.",
            hint = "Point the node at a parent-chain RPC that keeps full log history without a tight rate limit (Ethereum for " +
                "arb / base / robinhood, Arbitrum One for xai): an active node of that chain from this panel (same host: " +
                "http://127.0.0.1:<port>) or your own keyed provider, then restart the node. Shared public endpoints " +
                "(publicnode, arb1.arbitrum.io) are pruned and throttled.",
        )
    }

    private fun tailLog(nodeDir: String?): String?
    {
        val dir = nodeDir?.trim().orEmpty()
        if (dir.isEmpty())
        {
            return null
        }
        val path = Path.of(dir, "logs", "node.out")
        if (!Files.isRegularFile(path))
        {
            return null
        }
        return runCatching {
            Files.newByteChannel(path).use { ch ->
                val take = minOf(ch.size(), TAIL_BYTES.toLong()).toInt()
                ch.position(ch.size() - take)
                val buf = ByteBuffer.allocate(take)
                while (buf.hasRemaining() && ch.read(buf) > 0) { }
                String(buf.array(), 0, buf.position())
            }
        }.getOrNull()
    }
}
