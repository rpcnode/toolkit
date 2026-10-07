package rpcnode.toolkit.cdn.presentation

data class CdnConfig(
    /** Parent of `snapshots/`; null until chosen in install/menu. */
    val snapshotDir: String?,
    val pollSec: Long,
    val downloadJobs: Int = 4,
    val targetsFile: String,
    val envFile: String,
    /**
     * Public HTTP origin of this Snapshot CDN (no trailing slash), used to rewrite
     * Base V2 `manifest.json` `base_url` for `--manifest-url` downloads.
     */
    val publicOrigin: String? = null,
    val version: String = version(),
)
{
    companion object
    {
        fun version(): String
        {
            val stream = CdnConfig::class.java.classLoader.getResourceAsStream("cdn/version")
                ?: return "0.0.0-dev"
            return stream.bufferedReader().use { it.readText() }.trim().ifEmpty { "0.0.0-dev" }
        }
    }
}
