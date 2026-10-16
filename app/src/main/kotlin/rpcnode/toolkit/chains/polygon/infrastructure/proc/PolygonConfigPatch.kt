package rpcnode.toolkit.chains.polygon.infrastructure.proc

/** TOML rewrites for Bor / Heimdall configs under node_dir. */
internal object PolygonConfigPatch
{
    private val MONIKER = Regex("""(?m)^(moniker\s*=\s*")([^"]*)(".*)$""")
    private val VALID_MONIKER = Regex("[ -~]+")

    /**
     * CometBFT refuses to start with an empty or non-ASCII `moniker` (`info.Moniker must be valid non-empty ASCII text`). The
     * heimdall-v2 amoy package ships `moniker = ""`. A usable name set by the operator (or by an earlier run) is kept; an empty,
     * non-ASCII or tab-containing one becomes [fallback] (a sanitised host name). A config without any `moniker` line gets one.
     */
    fun ensureMoniker(text: String, fallback: String): String
    {
        val name = fallback.filter { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' || it == '.' }
            .take(40).ifEmpty { "rpcnode-heimdall" }
        val found = MONIKER.find(text)
        if (found == null)
        {
            // top level: before the first table so it stays a root key
            val firstTable = Regex("(?m)^\\[").find(text)?.range?.first ?: text.length
            return text.substring(0, firstTable) + "moniker = \"$name\"\n" + text.substring(firstTable)
        }
        val current = found.groupValues[2]
        if (current.isNotBlank() && VALID_MONIKER.matches(current) && '\t' !in current) return text
        return text.replaceRange(found.range, found.groupValues[1] + name + found.groupValues[3])
    }

    /**
     * Sentry Bor configs ship a commented `[heimdall]` block; Bor then falls back to
     * `http://localhost:1317`. Force an active section with the catalog REST URL.
     */
    fun rewriteHeimdallUrl(text: String, url: String): String
    {
        val block =
            """
            |[heimdall]
            |    url = "$url"
            |    "bor.without" = false
            |    grpc-address = ""
            """.trimMargin()
        val commented = Regex(
            """(?ms)^#\s*\[heimdall\][^\n]*\n(?:[ \t]*#[^\n]*\n)*""",
        )
        if (commented.containsMatchIn(text))
        {
            return text.replace(commented, block + "\n\n")
        }
        val active = Regex("""(?ms)^\[heimdall\][^\[]*(?=^\[|\z)""")
        if (active.containsMatchIn(text))
        {
            return text.replace(active, block + "\n\n")
        }
        return text.trimEnd() + "\n\n" + block + "\n"
    }
}
