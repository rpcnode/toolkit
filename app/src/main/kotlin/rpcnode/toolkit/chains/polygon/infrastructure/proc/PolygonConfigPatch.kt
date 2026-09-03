package rpcnode.toolkit.chains.polygon.infrastructure.proc

/** TOML rewrites for Bor / Heimdall configs under node_dir. */
internal object PolygonConfigPatch
{
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
