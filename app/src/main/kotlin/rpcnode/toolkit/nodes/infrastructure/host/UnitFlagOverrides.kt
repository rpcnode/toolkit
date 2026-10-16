package rpcnode.toolkit.nodes.infrastructure.host

import java.util.Base64

/**
 * Operator flag overrides for any systemd-unit client, applied to the rendered unit just before it is written
 * (the same hook as [NodeProxyEnv.withEnvironmentFile]).
 *
 * Text format (`install_options.extra_flags`): one `key=value` (or `key value`, or bare `key`) per line, key without
 * the leading `--`. A key that the unit already sets has its value replaced; a new key is appended in the unit's own
 * style (`--k=v` or `--k v`). Ports, paths, keys and network selection stay with the catalog ([isLocked]).
 */
object UnitFlagOverrides
{
    const val ARG_PREFIX = "--toolkit-extra-flags="

    /** Upper-case letters allowed: Nethermind keys are PascalCase (`JsonRpc.MaxBatchSize`). */
    private val KEY = Regex("^[A-Za-z][A-Za-z0-9._\\-]{0,63}$")

    /** Everything a value may contain: no whitespace, quotes, `$`, `\`, `;` or backticks — it lands in a unit file. */
    private val VALUE = Regex("^[A-Za-z0-9_.,:/*@+=\\-]{0,256}$")

    private val LOCKED_TOKENS = setOf(
        "port", "datadir", "dir", "path", "jwt", "jwtsecret", "secret", "keypair", "identity",
        "addr", "address", "chain", "network", "networkid", "genesis", "ledger", "config", "conf",
    )

    /**
     * PascalCase keys (`Network.P2PPort`, `JsonRpc.JwtSecretFile`) have no separators between words, so a token match is
     * not enough: for them the lower-cased property name (the part after the last dot; the leading section such as
     * `Network` is not a sign of a network selector) is searched for the locked words, and for `host` (the bind address).
     */
    private val PASCAL_LOCKED = LOCKED_TOKENS + "host"

    /** Port / path / secret / network keys come from the catalog and cannot be overridden. */
    fun isLocked(key: String): Boolean
    {
        if (key.any { it.isUpperCase() })
        {
            val property = key.substringAfterLast('.').lowercase()
            return PASCAL_LOCKED.any { it in property }
        }
        return key.split('.', '-', '_').any { it in LOCKED_TOKENS }
    }

    data class Parsed(val overrides: LinkedHashMap<String, String>, val rejected: List<String>)

    fun parse(text: String?): Parsed
    {
        val out = linkedMapOf<String, String>()
        val rejected = mutableListOf<String>()
        for (raw in text.orEmpty().lines())
        {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#"))
            {
                continue
            }
            val body = line.removePrefix("--")
            val cut = body.indexOfFirst { it == '=' || it == ' ' }
            val key = (if (cut < 0) body else body.substring(0, cut)).trim()
            val value = if (cut < 0) "" else body.substring(cut + 1).trim()
            if (!KEY.matches(key) || !VALUE.matches(value) || isLocked(key))
            {
                rejected += line
                continue
            }
            out[key] = value
        }
        return Parsed(out, rejected)
    }

    fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    fun decode(encoded: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(encoded)) }.getOrDefault("")

    /** The override text carried in launch args, or empty. */
    fun fromArgs(args: List<String>): String =
        args.firstOrNull { it.startsWith(ARG_PREFIX) }?.removePrefix(ARG_PREFIX)?.let(::decode).orEmpty()

    const val FILE = ".toolkit/extra_flags"

    /** Validated `key=value` lines for shell scripts; the file is deleted when nothing is left. */
    fun writeFile(nodeDir: java.nio.file.Path, text: String?)
    {
        val file = nodeDir.resolve(FILE)
        val lines = parse(text).overrides.entries.joinToString("") { (k, v) -> "$k=$v\n" }
        runCatching {
            if (lines.isEmpty())
            {
                java.nio.file.Files.deleteIfExists(file)
            }
            else
            {
                java.nio.file.Files.createDirectories(file.parent)
                java.nio.file.Files.writeString(file, lines)
            }
        }
    }

    fun applyFromArgs(unitBody: String, args: List<String>): String = apply(unitBody, fromArgs(args))

    /** Rewrites the unit's `ExecStart=` command; the body is returned unchanged when there is nothing to apply. */
    fun apply(unitBody: String, text: String?): String
    {
        val overrides = parse(text).overrides
        if (overrides.isEmpty())
        {
            return unitBody
        }
        val lines = unitBody.lines()
        val start = lines.indexOfFirst { it.startsWith("ExecStart=") }
        if (start < 0)
        {
            return unitBody
        }
        var end = start
        while (lines[end].trimEnd().endsWith("\\") && end + 1 < lines.size)
        {
            end++
        }
        val command = lines.subList(start, end + 1).joinToString(" ") { it.trimEnd().removeSuffix("\\") }
            .removePrefix("ExecStart=")
        val tokens = tokenize(command) ?: return unitBody
        val rebuilt = rewrite(tokens, overrides)
        val block = buildString {
            append("ExecStart=").append(rebuilt.head)
            for (f in rebuilt.flags)
            {
                append(" \\\n  ").append(f)
            }
        }
        return (lines.subList(0, start) + block.lines() + lines.subList(end + 1, lines.size)).joinToString("\n")
    }

    private class Rebuilt(val head: String, val flags: List<String>)

    /** Splits on whitespace honouring `"..."` / `'...'`; null when a quote is left open. */
    private fun tokenize(command: String): List<String>?
    {
        var open = false
        val out = buildList {
            val cur = StringBuilder()
            var quote = '\u0000'
            for (ch in command)
            {
                when
                {
                    quote != '\u0000' ->
                    {
                        cur.append(ch)
                        if (ch == quote) quote = '\u0000'
                    }
                    ch == '"' || ch == '\'' ->
                    {
                        cur.append(ch)
                        quote = ch
                    }
                    ch.isWhitespace() ->
                    {
                        if (cur.isNotEmpty())
                        {
                            add(cur.toString())
                            cur.clear()
                        }
                    }
                    else -> cur.append(ch)
                }
            }
            open = quote != '\u0000'
            if (cur.isNotEmpty()) add(cur.toString())
        }
        return if (open) null else out
    }

    private fun rewrite(tokens: List<String>, overrides: LinkedHashMap<String, String>): Rebuilt
    {
        // head = binary and any sub-command before the first --flag
        val firstFlag = tokens.indexOfFirst { it.startsWith("--") }.let { if (it < 0) tokens.size else it }
        val head = tokens.subList(0, firstFlag).joinToString(" ")
        // each flag entry: "--k=v", or "--k" followed by its value tokens (anything not starting with "--")
        val entries = mutableListOf<MutableList<String>>()
        var i = firstFlag
        while (i < tokens.size)
        {
            val entry = mutableListOf(tokens[i])
            i++
            while (i < tokens.size && !tokens[i].startsWith("--"))
            {
                entry += tokens[i]
                i++
            }
            entries += entry
        }
        fun keyOf(e: List<String>) = e[0].removePrefix("--").substringBefore('=')
        val equalsStyle = entries.count { '=' in it[0] } > entries.size / 2
        val seen = mutableSetOf<String>()
        val flags = mutableListOf<String>()
        // A key the unit repeats (e.g. several --node.feed.input.url) is a list: replacing one entry would silently
        // drop the others, so such flags are left exactly as the unit has them.
        val repeated = entries.groupingBy { keyOf(it) }.eachCount().filterValues { it > 1 }.keys
        for (e in entries)
        {
            val key = keyOf(e)
            val override = overrides[key]
            if (override == null || key in repeated)
            {
                flags += e.joinToString(" ")
                seen += key
                continue
            }
            seen += key
            flags += render(key, override, '=' in e[0] || (equalsStyle && e.size == 1))
        }
        for ((key, value) in overrides)
        {
            if (key !in seen)
            {
                flags += render(key, value, equalsStyle)
            }
        }
        return Rebuilt(head, flags)
    }

    private fun render(key: String, value: String, equals: Boolean): String =
        when
        {
            value.isEmpty() -> "--$key"
            equals -> "--$key=$value"
            else -> "--$key $value"
        }
}
