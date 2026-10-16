package rpcnode.toolkit.chains.cometbft.infrastructure

/**
 * Line-level TOML patch that keeps comments and everything it does not touch. A key is replaced where it is already
 * set inside its section; otherwise it is appended at the end of the section (the section is created when missing).
 */
object CometbftTomlPatch
{
    private val header = Regex("""^\s*\[\[?\s*([^\]]+?)\s*]]?\s*(#.*)?$""")

    fun apply(text: String, settings: List<TomlSetting>): String
    {
        var out = text
        for (s in settings)
        {
            out = set(out, s.section, s.key, s.tomlValue())
        }
        return out
    }

    fun set(text: String, section: String, key: String, tomlValue: String): String
    {
        val lines = text.split("\n").toMutableList()
        val keyLine = Regex("""^\s*${Regex.escape(key)}\s*=""")
        var inSection = section.isEmpty()
        var sectionFound = section.isEmpty()
        var lastInSection = -1
        var replaced = false
        for (i in lines.indices)
        {
            val m = header.matchEntire(lines[i])
            if (m != null)
            {
                if (inSection && !replaced && section.isNotEmpty()) break
                inSection = m.groupValues[1] == section
                if (inSection) sectionFound = true
                if (inSection) lastInSection = i
                if (section.isEmpty() && !inSection) break
                continue
            }
            if (!inSection) continue
            if (lines[i].isNotBlank()) lastInSection = i
            if (!replaced && keyLine.containsMatchIn(lines[i]))
            {
                val indent = lines[i].takeWhile { it == ' ' || it == '\t' }
                lines[i] = "$indent$key = $tomlValue"
                replaced = true
            }
        }
        if (replaced) return lines.joinToString("\n")
        val line = "$key = $tomlValue"
        if (section.isEmpty())
        {
            // top-level keys must precede the first table
            val firstHeader = lines.indexOfFirst { header.matches(it) }
            val at = if (firstHeader < 0) lines.size else firstHeader
            lines.add(at, line)
            if (firstHeader >= 0 && lines.getOrNull(at + 1)?.isNotBlank() == true) lines.add(at + 1, "")
            return lines.joinToString("\n")
        }
        if (!sectionFound)
        {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.lastIndex)
            lines += listOf("", "[$section]", line, "")
            return lines.joinToString("\n")
        }
        lines.add(lastInSection + 1, line)
        return lines.joinToString("\n")
    }
}
