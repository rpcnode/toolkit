package rpcnode.toolkit.cdn.application.sync

data class SnapshotTarget(
    val network: String,
    val env: String,
    val type: String = "full",
)
{
    val id: String get() = "$network/$env/$type"
}

enum class SnapshotMirrorKind
{
    /** Single archive file (TRON .tgz, etc.). */
    ARCHIVE_FILE,

    /** Base V2 modular tree: manifest.json + segment objects. */
    BASE_MANIFEST,
}

data class OfficialSnapshot(
    val network: String,
    val env: String,
    val type: String,
    val url: String,
    val version: String,
    val filename: String,
    val sizeBytes: Long?,
    val kind: SnapshotMirrorKind = SnapshotMirrorKind.ARCHIVE_FILE,
)

/**
 * Local list of targets to mirror, plus official upstream discovery.
 * Null from [listTargets] means the store could not be read — keep current workers.
 */
interface SnapshotSource
{
    suspend fun listTargets(): List<SnapshotTarget>?
    suspend fun officialSnapshot(target: SnapshotTarget): OfficialSnapshot?
}

fun snapshotDateFromVersion(version: String): String
{
    val m = Regex("""backup(\d{4})(\d{2})(\d{2})""").find(version) ?: return version
    val (y, mo, d) = m.destructured
    return "$y-$mo-$d"
}

data class MirrorEntry(
    val network: String,
    val env: String,
    val type: String,
    val version: String,
    val date: String,
    val sizeBytes: Long?,
    val filename: String,
    /** Relative path under /snapshots/ for the site download link. */
    val path: String = "$network/$env/$type/$filename",
    /** When this mirror finished downloading (ISO-8601 UTC), if known. */
    val updatedAt: String? = null,
)


interface SnapshotMirrorStore
{
    suspend fun currentVersion(network: String, env: String, type: String): String?
    suspend fun describe(network: String, env: String, type: String): MirrorEntry?
    suspend fun publish(
        network: String,
        env: String,
        type: String,
        version: String,
        filename: String,
        sourceUrl: String,
        sizeBytes: Long?,
    )

    /**
     * Mirror a Base V2 archive tree: download [manifestUrl] + segments under the type dir,
     * rewrite `base_url` to [publicOrigin]/snapshots/{network}/{env}/{type}, publish
     * `…/{version}/manifest.json` and site.json.
     */
    suspend fun publishBaseManifest(
        network: String,
        env: String,
        type: String,
        version: String,
        manifestUrl: String,
        sizeBytes: Long?,
        publicOrigin: String,
    )

    /** Rewrite `snapshots/index.json` from on-disk `site.json` cards (locked). */
    suspend fun rebuildPublicIndex()
    suspend fun writeIndex(entries: List<MirrorEntry>)
    suspend fun listPublished(): List<MirrorEntry>
}

