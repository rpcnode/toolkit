package rpcnode.toolkit.nodes.application.config

/**
 * Line-oriented patch for HOCON / INI client templates.
 * Sets assignment leaves for binding paths without a full HOCON AST.
 *
 * Bitcoin Core INI: network-specific keys (`port`, `rpcport`, `rpcbind`, `bind`, …) must live
 * under `[main]`, `[test]`, `[testnet4]`, `[signet]`, or `[regtest]` — not in the default
 * (pre-section) area. See doc/bitcoin-conf.md «Network specific options».
 */
object ClientConfigLeafPatch
{
    /** Keys that stay in the default (pre-section) area for multi-env INI files. */
    internal val INI_GLOBAL_KEYS = setOf("datadir", "blocksdir")

    private val INI_SECTION_HEADER = Regex("(?m)^\\[[^]]+\\]\\s*$")

    fun applyHocon(template: String, assignments: Map<String, String>): String
    {
        var text = template
        for ((path, raw) in assignments)
        {
            val value = formatHoconValue(raw)
            val keys = leafKeyCandidates(path)
            var replaced = false
            for (key in keys)
            {
                val next = replaceHoconAssign(text, key, value)
                if (next != null)
                {
                    text = next
                    replaced = true
                    break
                }
            }
            if (!replaced)
            {
                text = text.trimEnd() + "\n\n# toolkit override\n$path = $value\n"
            }
        }
        return text
    }

    fun applyIni(
        template: String,
        assignments: Map<String, String>,
        section: String? = null,
        omitKeys: Set<String> = emptySet(),
    ): String
    {
        val sectionName = section?.trim()?.ifEmpty { null }
        var text = template
        for (key in omitKeys)
        {
            text = commentIniKeyEverywhere(text, key, "toolkit: optional binding not used")
        }
        if (sectionName == null)
        {
            return applyIniFlat(text, assignments)
        }
        text = ensureIniSection(text, sectionName)
        for ((path, raw) in assignments)
        {
            val key = iniLeafKey(path)
            text = if (key in INI_GLOBAL_KEYS)
            {
                patchIniDefaultSection(text, key, raw)
            }
            else
            {
                patchIniNetworkSection(text, sectionName, key, raw)
            }
        }
        return text
    }

    internal fun leafKeyCandidates(path: String): List<String>
    {
        val parts = path.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty())
        {
            return emptyList()
        }
        val out = mutableListOf<String>()
        for (i in parts.indices)
        {
            out += parts.subList(i, parts.size).joinToString(".")
        }
        return out
    }

    internal fun formatHoconValue(raw: String): String
    {
        val v = raw.trim()
        if (v.matches(Regex("""^-?\d+(\.\d+)?$""")))
        {
            return v
        }
        if (v == "true" || v == "false")
        {
            return v
        }
        val escaped = v.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    internal fun findIniSectionRange(text: String, section: String): IntRange?
    {
        val header = Regex("(?m)^\\[${Regex.escape(section)}\\]\\s*$")
        val match = header.find(text) ?: return null
        val start = match.range.last + 1
        val nextSection = INI_SECTION_HEADER.find(text, start)
        val endExclusive = nextSection?.range?.first ?: text.length
        if (endExclusive <= start)
        {
            return start until endExclusive
        }
        return start until endExclusive
    }

    internal fun iniLeafKey(path: String): String =
        path.substringAfterLast('.').ifBlank { path }

    private fun applyIniFlat(template: String, assignments: Map<String, String>): String
    {
        var text = template
        for ((path, raw) in assignments)
        {
            val key = iniLeafKey(path)
            val next = replaceIniAssign(text, key, raw)
            text = next ?: (text.trimEnd() + "\n$key=$raw\n")
        }
        return text
    }

    private fun ensureIniSection(text: String, section: String): String
    {
        if (findIniSectionRange(text, section) != null)
        {
            return text
        }
        return text.trimEnd() + "\n\n[$section]\n"
    }

    private fun patchIniDefaultSection(text: String, key: String, value: String): String
    {
        val splitAt = firstIniSectionStart(text) ?: text.length
        val defaultPart = text.substring(0, splitAt)
        val rest = text.substring(splitAt)
        val patched = replaceIniAssign(defaultPart, key, value)
            ?: defaultPart.trimEnd() + "\n$key=$value\n"
        return patched + rest
    }

    private fun patchIniNetworkSection(
        text: String,
        section: String,
        key: String,
        value: String,
    ): String
    {
        var next = commentIniKeyOutsideSection(text, section, key)
        val replaced = replaceIniInSection(next, section, key, value)
        next = replaced ?: appendIniSectionKey(next, section, key, value)
        return next
    }

    private fun firstIniSectionStart(text: String): Int? =
        INI_SECTION_HEADER.find(text)?.range?.first

    private fun replaceHoconAssign(text: String, key: String, value: String): String?
    {
        val escaped = Regex.escape(key)
        val re = Regex("""(?m)^(\s*)$escaped\s*=\s*.*$""")
        val matches = re.findAll(text).toList()
        if (matches.isEmpty())
        {
            return null
        }
        if (matches.size > 1 && !key.contains('.'))
        {
            return null
        }
        var first = true
        return re.replace(text) { m ->
            if (!first)
            {
                return@replace m.value
            }
            first = false
            "${m.groupValues[1]}$key = $value"
        }
    }

    private fun replaceIniAssign(text: String, key: String, value: String): String?
    {
        val escaped = Regex.escape(key)
        val re = Regex("""(?m)^#?\s*$escaped\s*=\s*.*$""")
        if (!re.containsMatchIn(text))
        {
            return null
        }
        return re.replaceFirst(text, "$key=$value")
    }

    private fun replaceIniInSection(text: String, section: String, key: String, value: String): String?
    {
        val range = findIniSectionRange(text, section) ?: return null
        val slice = text.substring(range)
        val escaped = Regex.escape(key)
        val re = Regex("""(?m)^#?\s*$escaped\s*=\s*.*$""")
        if (!re.containsMatchIn(slice))
        {
            return null
        }
        val patched = re.replaceFirst(slice, "$key=$value")
        return text.substring(0, range.first) + patched + text.substring(range.last + 1)
    }

    private fun appendIniSectionKey(text: String, section: String, key: String, value: String): String
    {
        val range = findIniSectionRange(text, section)
            ?: return text.trimEnd() + "\n\n[$section]\n$key=$value\n"
        val insertAt = range.last + 1
        val before = text.substring(0, insertAt).trimEnd()
        val after = text.substring(insertAt)
        return "$before\n$key=$value\n$after"
    }

    /** Comment duplicate assignments outside the target network section (default + other sections). */
    private fun commentIniKeyOutsideSection(text: String, section: String, key: String): String
    {
        val sectionRange = findIniSectionRange(text, section)
        val escaped = Regex.escape(key)
        val re = Regex("""(?m)^(\s*)#?\s*($escaped\s*=.*)$""")
        val sb = StringBuilder()
        var last = 0
        var changed = false
        for (match in re.findAll(text))
        {
            if (sectionRange != null && match.range.first in sectionRange)
            {
                continue
            }
            sb.append(text.substring(last, match.range.first))
            val line = match.groupValues[2].trim()
            if (!line.startsWith("#"))
            {
                sb.append("# toolkit: use [$section] for this network\n# $line")
                changed = true
            }
            else
            {
                sb.append(match.value)
            }
            last = match.range.last + 1
        }
        if (!changed)
        {
            return text
        }
        sb.append(text.substring(last))
        return sb.toString()
    }

    private fun commentIniKeyEverywhere(text: String, key: String, reason: String): String
    {
        val escaped = Regex.escape(key)
        val re = Regex("""(?m)^(\s*)#?\s*($escaped\s*=.*)$""")
        val sb = StringBuilder()
        var last = 0
        var changed = false
        for (match in re.findAll(text))
        {
            sb.append(text.substring(last, match.range.first))
            val line = match.groupValues[2].trim()
            if (!line.startsWith("#"))
            {
                sb.append("# $reason\n# $line")
                changed = true
            }
            else
            {
                sb.append(match.value)
            }
            last = match.range.last + 1
        }
        if (!changed)
        {
            return text
        }
        sb.append(text.substring(last))
        return sb.toString()
    }
}
