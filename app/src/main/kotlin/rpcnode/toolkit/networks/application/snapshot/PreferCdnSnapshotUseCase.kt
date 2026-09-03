package rpcnode.toolkit.networks.application.snapshot

sealed interface PreferCdnSnapshotResult
{
    data class Resolved(
        val url: String?,
        val officialUrl: String?,
        val version: String?,
        /** `cdn` or `official`. */
        val source: String,
        val streamUnpack: Boolean?,
        val sizeBytes: Long?,
        val typeId: String,
    ) : PreferCdnSnapshotResult

    data object UnknownNetwork : PreferCdnSnapshotResult
    data object UnknownEnv : PreferCdnSnapshotResult
    data class SourceUnavailable(
        val source: String,
        val detail: String,
        val typeId: String,
    ) : PreferCdnSnapshotResult
}

/**
 * Resolves the snapshot URL for download.
 *
 * When [source] is `official` or `cdn`, that origin is used if [ListSnapshotSourcesUseCase]
 * marks it available (CDN may differ from the live official VERSION when the operator picks it).
 * When [source] is null, prefers CDN only when its VERSION matches official; otherwise official.
 */
class PreferCdnSnapshotUseCase(
    private val listSources: ListSnapshotSourcesUseCase,
)
{
    suspend operator fun invoke(
        networkRaw: String,
        envRaw: String,
        source: String? = null,
        typeId: String = "",
    ): PreferCdnSnapshotResult
    {
        when (val listed = listSources(networkRaw, envRaw, typeId))
        {
            SnapshotSourcesResult.UnknownNetwork -> return PreferCdnSnapshotResult.UnknownNetwork
            SnapshotSourcesResult.UnknownEnv -> return PreferCdnSnapshotResult.UnknownEnv
            is SnapshotSourcesResult.Resolved ->
            {
                val want = normalizeSource(source)
                val pick = when (want)
                {
                    SOURCE_OFFICIAL, SOURCE_CDN ->
                        listed.sources.firstOrNull { it.id == want }
                    else -> autoPick(listed)
                }
                if (pick == null)
                {
                    return PreferCdnSnapshotResult.Resolved(
                        url = null,
                        officialUrl = listed.officialUrl,
                        version = listed.officialVersion,
                        source = SOURCE_OFFICIAL,
                        streamUnpack = null,
                        sizeBytes = null,
                        typeId = listed.typeId,
                    )
                }
                if (!pick.available || pick.url.isNullOrBlank())
                {
                    return PreferCdnSnapshotResult.SourceUnavailable(
                        source = pick.id,
                        detail = pick.detail ?: "Snapshot source is not available",
                        typeId = listed.typeId,
                    )
                }
                return PreferCdnSnapshotResult.Resolved(
                    url = pick.url,
                    officialUrl = listed.officialUrl,
                    version = pick.version,
                    source = pick.id,
                    streamUnpack = pick.streamUnpack,
                    sizeBytes = pick.sizeBytes,
                    typeId = listed.typeId,
                )
            }
        }
    }

    private fun autoPick(listed: SnapshotSourcesResult.Resolved): SnapshotSourceOption?
    {
        val cdn = listed.sources.firstOrNull { it.id == SOURCE_CDN }
        if (
            cdn?.available == true &&
            !listed.officialVersion.isNullOrBlank() &&
            cdn.version == listed.officialVersion &&
            !cdn.url.isNullOrBlank()
        )
        {
            return cdn
        }
        return listed.sources.firstOrNull { it.id == SOURCE_OFFICIAL && it.available }
            ?: listed.sources.firstOrNull { it.available }
    }

    private fun normalizeSource(source: String?): String? =
        when (source?.trim()?.lowercase())
        {
            SOURCE_OFFICIAL, SOURCE_CDN -> source.trim().lowercase()
            else -> null
        }

    companion object
    {
        const val SOURCE_CDN = "cdn"
        const val SOURCE_OFFICIAL = "official"
    }
}
