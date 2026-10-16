package rpcnode.toolkit.chains.substrate.infrastructure.http

import java.net.URI
import java.net.URLDecoder
import java.time.Duration
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateDb
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateMode
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpec
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/** One row of the table on `https://snapshots.polkadot.io/`. */
data class SubstrateSnapshotEntry(
    val name: String,
    val version: String,
    val timestamp: String,
    val lastBlock: Long,
    val sizeBytes: Long,
    val pruning: String,
    /** Dated base URL, e.g. `https://snapshots.polkadot.io/polkadot-paritydb-state-blocks-pruned/20261013-185609`. */
    val url: String,
)

/**
 * Parity / IBP database snapshots (docs.polkadot.com). The store is a plain file tree with a `files.txt` manifest, no
 * archive: the host agent downloads the files itself ([SubstrateSnapshotResolver.SCHEME] sentinel, like `mithril://`).
 * The listing is an HTML table without a JSON twin, so it is scraped; the dated URL rotates every day and is never cached.
 */
object SubstrateSnapshotIndex
{
    private val ROW = Regex("<tr>\\s*((?:<td>.*?</td>\\s*)+)</tr>", RegexOption.DOT_MATCHES_ALL)
    private val CELL = Regex("<td>(.*?)</td>", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("<[^>]+>")
    private val SIZE = Regex("""^([0-9]+(?:\.[0-9]+)?)\s*(GB|TB|MB)$""", RegexOption.IGNORE_CASE)

    fun parse(html: String): List<SubstrateSnapshotEntry> =
        ROW.findAll(html).mapNotNull { row ->
            val cells = CELL.findAll(row.groupValues[1]).map { TAG.replace(it.groupValues[1], "").trim() }.toList()
            if (cells.size < 7) return@mapNotNull null
            val size = sizeBytes(cells[4]) ?: return@mapNotNull null
            val url = cells[6].takeIf { it.startsWith("https://") } ?: return@mapNotNull null
            SubstrateSnapshotEntry(
                name = cells[0],
                version = cells[1],
                timestamp = cells[2],
                lastBlock = cells[3].toDoubleOrNull()?.toLong() ?: 0L,
                sizeBytes = size,
                pruning = cells[5],
                url = url.trimEnd('/'),
            )
        }.toList()

    /** `1138.28 GB` → bytes (decimal GB, as the page prints them); null for anything else. */
    fun sizeBytes(text: String): Long?
    {
        val m = SIZE.matchEntire(text.trim()) ?: return null
        val n = m.groupValues[1].toDouble()
        val unit = when (m.groupValues[2].uppercase())
        {
            "TB" -> 1e12
            "GB" -> 1e9
            else -> 1e6
        }
        return (n * unit).toLong()
    }
}

/**
 * `substrate-files://<host>/<listing name>/<date>?db=<paritydb|rocksdb>&chain=<chain dir id>&env=<env>` for the
 * snapshot of the node type [typeId] (`pruned` | `archive`). Null when the listing does not carry the snapshot.
 */
class SubstrateSnapshotResolver(
    private val spec: SubstrateSpec,
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(20))),
    private val listingUrl: String = LISTING_URL,
) : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val e = spec.envs.firstOrNull { it.id == env.value } ?: return null
        val mode = SubstrateMode.parse(typeId)
        val name = e.snapshotNames[mode] ?: return null
        val html = http.getText(listingUrl) ?: return null
        val entry = SubstrateSnapshotIndex.parse(html).firstOrNull { it.name == name } ?: return null
        val db = if (name.contains("-rocksdb-")) SubstrateDb.ROCKSDB else SubstrateDb.PARITYDB
        return SnapshotArchive(
            url = sentinel(entry.url, db, e.chainDirId, spec.network.value, e.id),
            streamUnpack = false,
            sizeBytes = entry.sizeBytes,
        )
    }

    /** What the agent runner needs from the sentinel. [baseUrl] is the dated `https://` listing directory. */
    data class Ref(val baseUrl: String, val db: SubstrateDb, val chainDirId: String, val network: String, val env: String)

    companion object
    {
        const val SCHEME = "substrate-files"
        const val LISTING_URL = "https://snapshots.polkadot.io/"

        fun isOfficialUrl(url: String): Boolean = url.trim().lowercase().startsWith("$SCHEME://")

        fun sentinel(datedUrl: String, db: SubstrateDb, chainDirId: String, network: String, env: String): String =
            datedUrl.replaceFirst("https://", "$SCHEME://") + "?db=${db.flag}&chain=$chainDirId&net=$network&env=$env"

        fun parse(url: String): Ref?
        {
            val raw = url.trim()
            if (!isOfficialUrl(raw)) return null
            val uri = runCatching { URI(raw) }.getOrNull() ?: return null
            val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
            val path = uri.path?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
            val query = (uri.rawQuery ?: "").split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            val db = SubstrateDb.entries.firstOrNull { it.flag == query["db"] } ?: return null
            val chain = query["chain"]?.takeIf { SAFE_ID.matches(it) } ?: return null
            val env = query["env"]?.takeIf { SAFE_ID.matches(it) } ?: return null
            val network = query["net"]?.takeIf { SAFE_ID.matches(it) } ?: return null
            if (!SAFE_PATH.matches(path) || path.contains("..")) return null
            return Ref(baseUrl = "https://$host$path", db = db, chainDirId = chain, network = network, env = env)
        }

        private val SAFE_ID = Regex("^[a-z0-9][a-z0-9_-]{0,31}$")
        private val SAFE_PATH = Regex("^/[A-Za-z0-9._/-]+$")
    }
}
