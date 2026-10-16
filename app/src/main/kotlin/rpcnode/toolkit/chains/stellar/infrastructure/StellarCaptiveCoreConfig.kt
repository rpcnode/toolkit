package rpcnode.toolkit.chains.stellar.infrastructure

/** Patches the synced captive-core config so its overlay port is the catalog one (port of the Go ApplyCaptiveCorePorts). */
object StellarCaptiveCoreConfig
{
    private val HTTP_PORT = Regex("(?m)^[ \\t]*HTTP_PORT[ \\t]*=.*\\n?")
    private val PEER_PORT = Regex("(?m)^[ \\t]*PEER_PORT[ \\t]*=.*\\n?")

    /**
     * Strips every HTTP_PORT / PEER_PORT line (also under `[[VALIDATORS]]`) and, when [peerPort] > 0, puts
     * `PEER_PORT=<n>` at the root level, before the first table.
     */
    fun applyPorts(cfg: String, peerPort: Int): String
    {
        val stripped = PEER_PORT.replace(HTTP_PORT.replace(cfg, ""), "")
        if (peerPort <= 0)
        {
            return stripped
        }
        val line = "PEER_PORT=$peerPort\n"
        val table = stripped.indexOf("[[")
        if (table >= 0)
        {
            return stripped.substring(0, table) + line + stripped.substring(table)
        }
        val base = if (stripped.isEmpty() || stripped.endsWith("\n")) stripped else "$stripped\n"
        return base + line
    }
}
