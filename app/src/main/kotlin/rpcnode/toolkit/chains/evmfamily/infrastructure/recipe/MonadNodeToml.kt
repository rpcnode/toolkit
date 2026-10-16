package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

/**
 * The vendor `full-node-node.toml` of a Monad network, turned into this node's `node.toml`: the node name, the bind ports of
 * `[network]` and the own name record of `[peer_discovery]`. Everything else (peer and bootstrap lists, statesync peers,
 * raptorcast limits) stays as the vendor ships it. Line based on purpose: a TOML round trip would drop the vendor's comments.
 */
object MonadNodeToml
{
    /** `key = value` lines printed by `monad-sign-name-record`: `self_address`, `self_record_seq_num`, `self_name_record_sig`, … */
    fun parseNameRecord(output: String): Map<String, String>
    {
        val out = linkedMapOf<String, String>()
        for (raw in output.lineSequence())
        {
            val m = SELF_LINE.matchEntire(raw.trim()) ?: continue
            out[m.groupValues[1]] = tomlValue(m.groupValues[2].trim())
        }
        return out
    }

    /** A printed value as TOML: numbers and already quoted strings stay, anything else becomes a basic string. */
    fun tomlValue(printed: String): String =
        if (printed.matches(Regex("""\d+""")) || printed.startsWith("\"")) printed else quote(printed)

    /** `[network]` keys: mainnet binds one shared TCP/UDP port; testnet splits the TCP port and adds the direct UDP one. */
    fun networkSection(env: String, p2p: Int, authUdp: Int, directUdp: Int): Map<String, String> =
        if (env == "testnet")
        {
            linkedMapOf(
                "bind_address_tcp_port" to p2p.toString(),
                "authenticated_bind_address_port" to authUdp.toString(),
                "direct_udp_bind_address_port" to directUdp.toString(),
            )
        }
        else
        {
            linkedMapOf(
                "bind_address_port" to p2p.toString(),
                "authenticated_bind_address_port" to authUdp.toString(),
            )
        }

    /** Ports of the unsigned part of `[peer_discovery]` (the signed values come from [parseNameRecord]). */
    fun peerSection(env: String, p2p: Int, authUdp: Int, directUdp: Int): Map<String, String> =
        if (env == "testnet")
        {
            linkedMapOf(
                "self_tcp_port" to p2p.toString(),
                "self_auth_port" to authUdp.toString(),
                "self_direct_udp_auth_port" to directUdp.toString(),
            )
        }
        else
        {
            linkedMapOf("self_auth_port" to authUdp.toString())
        }

    /**
     * [template] with `node_name` set and [sections] (section name → key → TOML value text) applied: an existing key is
     * rewritten in place, a missing one is appended at the end of its section. A section the template lacks is an error.
     */
    fun patch(template: String, nodeName: String, sections: Map<String, Map<String, String>>): String
    {
        val lines = template.lines().toMutableList()
        val missingSections = sections.keys.filter { name -> lines.none { it.trim() == "[$name]" } }
        require(missingSections.isEmpty()) { "node.toml has no section ${missingSections.joinToString { "[$it]" }}" }

        var section = ""
        var nameSet = false
        val seen = mutableMapOf<String, MutableSet<String>>()
        val lastLineOf = mutableMapOf<String, Int>()
        for (i in lines.indices)
        {
            val t = lines[i].trim()
            val header = SECTION.matchEntire(t)
            if (header != null)
            {
                section = if (t.startsWith("[[")) "[[${header.groupValues[2]}]]" else header.groupValues[2]
                continue
            }
            if (t.isEmpty() || t.startsWith("#")) continue
            lastLineOf[section] = i
            val key = KEY.matchEntire(t)?.groupValues?.get(1) ?: continue
            if (section == "" && key == "node_name")
            {
                lines[i] = "node_name = ${quote(nodeName)}"
                nameSet = true
                continue
            }
            val wanted = sections[section] ?: continue
            val value = wanted[key] ?: continue
            lines[i] = "$key = $value"
            seen.getOrPut(section) { mutableSetOf() } += key
        }
        require(nameSet) { "node.toml has no node_name" }

        // append the keys a section lacks, last section first so earlier indexes stay valid
        for ((name, wanted) in sections.entries.sortedByDescending { lastLineOf[it.key] ?: -1 })
        {
            val missing = wanted.filterKeys { it !in seen[name].orEmpty() }
            if (missing.isEmpty()) continue
            val at = (lastLineOf[name] ?: lines.indexOfFirst { it.trim() == "[$name]" }) + 1
            lines.addAll(at, missing.map { (k, v) -> "$k = $v" })
        }
        return lines.joinToString("\n")
    }

    /** Value of `self_address` inside `[peer_discovery]` of an existing node.toml, null when absent. */
    fun peerValue(toml: String, key: String): String?
    {
        var inside = false
        for (raw in toml.lineSequence())
        {
            val t = raw.trim()
            if (SECTION.matches(t)) inside = t == "[peer_discovery]"
            if (!inside) continue
            val m = KEY_VALUE.matchEntire(t) ?: continue
            if (m.groupValues[1] == key) return m.groupValues[2].substringBefore(" #").trim().trim('"')
        }
        return null
    }

    fun quote(raw: String): String = "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val SELF_LINE = Regex("""(self_[a-z_0-9]+)\s*=\s*(.+)""")
    private val SECTION = Regex("""(\[\[?)([A-Za-z0-9_.\-]+)\]\]?(\s*#.*)?""")
    private val KEY = Regex("""([A-Za-z0-9_\-]+)\s*=.*""")
    private val KEY_VALUE = Regex("""([A-Za-z0-9_\-]+)\s*=\s*(.*)""")
}
