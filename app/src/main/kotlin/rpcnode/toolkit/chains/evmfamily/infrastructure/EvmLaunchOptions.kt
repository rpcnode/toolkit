package rpcnode.toolkit.chains.evmfamily.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What the wizard chose, as it travels in `NodeLaunchSpec.args` from the panel to the agent:
 * `--toolkit-env`, `--toolkit-mode` (install option `node`), `--toolkit-snapshot` (install option `snapshot`) and one
 * `--toolkit-dir-<role>=<absolute dir>` per disk role.
 */
data class EvmLaunchOptions(
    val env: String,
    val mode: String = FULL,
    val snapshot: String = "",
    val dirs: Map<String, String> = emptyMap(),
    /** Free-text wizard options named by [EvmProfile.optionKeys]. */
    val extra: Map<String, String> = emptyMap(),
)
{
    val archive: Boolean get() = mode == ARCHIVE

    fun toArgs(): List<String> = buildList {
        add("$ENV=$env")
        add("$MODE=$mode")
        if (snapshot.isNotEmpty()) add("$SNAPSHOT=$snapshot")
        for ((role, dir) in dirs.toSortedMap()) add("$DIR$role=$dir")
        for ((key, value) in extra.toSortedMap()) add("$OPT$key=$value")
    }

    companion object
    {
        const val FULL = "full"
        const val ARCHIVE = "archive"
        const val ENV = "--toolkit-env"
        const val MODE = "--toolkit-mode"
        const val SNAPSHOT = "--toolkit-snapshot"
        const val DIR = "--toolkit-dir-"
        const val OPT = "--toolkit-opt-"

        private val json = Json { ignoreUnknownKeys = true }

        /** `node` option of the wizard: `archive` or (default) `full`. */
        fun modeFrom(installOptionsJson: String?): String =
            if (option(installOptionsJson, "node")?.lowercase() == ARCHIVE) ARCHIVE else FULL

        /** Values of the install options [keys] that the operator filled in (blank ones are left out). */
        fun extraFrom(installOptionsJson: String?, keys: List<String>): Map<String, String> =
            keys.mapNotNull { key -> option(installOptionsJson, key)?.let { key to it } }.toMap()

        /** `snapshot` option of the wizard (the snapshot type id) or empty. */
        fun snapshotFrom(installOptionsJson: String?): String = option(installOptionsJson, "snapshot")?.lowercase().orEmpty()

        private fun option(installOptionsJson: String?, key: String): String?
        {
            val raw = installOptionsJson?.trim().orEmpty()
            if (raw.isEmpty()) return null
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
            return root[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        }

        fun parse(args: List<String>): EvmLaunchOptions
        {
            fun value(prefix: String) = args.firstOrNull { it.startsWith("$prefix=") }?.substringAfter('=')?.trim().orEmpty()
            val dirs = args.filter { it.startsWith(DIR) && '=' in it }
                .associate { it.removePrefix(DIR).substringBefore('=') to it.substringAfter('=').trim() }
                .filterValues { it.startsWith("/") && !it.contains("..") }
            val extra = args.filter { it.startsWith(OPT) && '=' in it }
                .associate { it.removePrefix(OPT).substringBefore('=') to it.substringAfter('=').trim() }
                .filterValues { it.isNotEmpty() }
            return EvmLaunchOptions(
                env = value(ENV).ifEmpty { "mainnet" },
                mode = if (value(MODE) == ARCHIVE) ARCHIVE else FULL,
                snapshot = value(SNAPSHOT),
                dirs = dirs,
                extra = extra,
            )
        }
    }
}
