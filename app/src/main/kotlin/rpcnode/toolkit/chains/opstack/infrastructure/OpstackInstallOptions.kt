package rpcnode.toolkit.chains.opstack.infrastructure

import kotlinx.serialization.json.Json
import rpcnode.toolkit.catalog.domain.NetworkId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Install option group `node` — full (pruned) or archive. */
object OpstackNodeMode
{
    const val FULL = "full"
    const val ARCHIVE = "archive"

    fun normalize(raw: String?): String = if (raw?.trim()?.lowercase() == ARCHIVE) ARCHIVE else FULL
}

/**
 * Install option group `restore` — how the datadir is initialised on the first start: from a snapshot (default; the network's
 * own source or the operator's `snapshot_url`) or by syncing without one. The group id is `restore`, not `snapshot`: the wizard
 * treats groups called `snapshot*` as the snapshot-type step, which a family with its own restore does not have.
 */
object OpstackRestoreMode
{
    const val SNAPSHOT = "snapshot"
    const val NONE = "none"

    fun normalize(raw: String?): String = if (raw?.trim()?.lowercase() == NONE) NONE else SNAPSHOT

    /**
     * What a node without a saved `restore` choice does. Unichain syncs from scratch in hours (Uniswap's docs quote 4–8 h)
     * and its only snapshot source is third-party, so it starts without one; every other network restores.
     */
    fun defaultFor(network: NetworkId?): String = if (network == NetworkId.UNICHAIN) NONE else SNAPSHOT
}

/** What the wizard saved for an OP Stack node (`install_options` JSON). */
data class OpstackInstallOptions(
    val node: String = OpstackNodeMode.FULL,
    val restore: String = OpstackRestoreMode.SNAPSHOT,
    /** Operator datadir archive (.tar / .tar.gz / .tar.lz4 / .tar.zst): wins over the built-in source. Blank = none. */
    val snapshotUrl: String = "",
)
{
    companion object
    {
        fun fromJson(installOptionsJson: String?, network: NetworkId? = null): OpstackInstallOptions
        {
            val raw = installOptionsJson?.trim().orEmpty()
            val root: JsonObject? = if (raw.isEmpty()) null else runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
            fun text(key: String) = root?.get(key)?.jsonPrimitive?.contentOrNull
            return OpstackInstallOptions(
                node = OpstackNodeMode.normalize(text("node")),
                restore = text("restore")?.let(OpstackRestoreMode::normalize) ?: OpstackRestoreMode.defaultFor(network),
                snapshotUrl = text("snapshot_url")?.trim().orEmpty(),
            )
        }
    }
}

/**
 * The only snapshot URLs the run script accepts from an operator: `http(s)`, no whitespace / quotes / shell metacharacters,
 * and a recognised archive suffix on the path (the format is chosen by it; an unknown one would download hundreds of GB
 * and then fail to extract).
 */
object OpstackSnapshotUrl
{
    private val allowed = Regex("""^https?://[A-Za-z0-9._~:/?#\[\]@!&+,=%-]+$""")
    private val suffixes = listOf(".tar.zst", ".tar.lz4", ".tar.gz", ".tgz", ".tar")

    /** Null when [url] is acceptable, else a one-line reason. */
    fun problem(url: String): String?
    {
        val u = url.trim()
        if (u.isEmpty()) return null
        if (!allowed.matches(u)) return "snapshot_url must be an http(s) URL without spaces or quotes"
        val path = u.substringBefore('?').substringBefore('#').lowercase()
        if (suffixes.none { path.endsWith(it) })
        {
            return "snapshot_url must end in .tar, .tar.gz, .tgz, .tar.lz4 or .tar.zst"
        }
        return null
    }
}
