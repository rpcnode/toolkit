package rpcnode.toolkit.networks.application.snapshot

import rpcnode.toolkit.chains.base.infrastructure.BaseClusters
import rpcnode.toolkit.chains.base.infrastructure.http.BaseSnapshotResolver
import rpcnode.toolkit.chains.base.infrastructure.http.BaseSnapshotTipProbe
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin
import rpcnode.toolkit.settings.domain.repository.SettingsStore

/**
 * Lists official and Snapshot CDN origins for a network/env/type.
 * Does not pick a winner — [PreferCdnSnapshotUseCase] resolves the operator's choice.
 */
class ListSnapshotSourcesUseCase(
    private val resolve: ResolveSnapshotUseCase,
    private val store: SettingsStore,
    private val probe: CdnMirrorProbe,
    private val envSnapshotCdnOrigin: String?,
    private val baseTip: BaseSnapshotTipProbe? = null,
)
{
    suspend operator fun invoke(
        networkRaw: String,
        envRaw: String,
        typeId: String = "",
    ): SnapshotSourcesResult
    {
        when (val resolved = resolve(networkRaw, envRaw, typeId))
        {
            ResolveSnapshotResult.UnknownNetwork -> return SnapshotSourcesResult.UnknownNetwork
            ResolveSnapshotResult.UnknownEnv -> return SnapshotSourcesResult.UnknownEnv
            is ResolveSnapshotResult.Resolved ->
            {
                val type = resolved.typeId.ifBlank { typeId.trim().lowercase() }.ifBlank { "full" }
                val archive = resolved.archive
                if (archive == null)
                {
                    return SnapshotSourcesResult.Resolved(
                        typeId = type,
                        officialUrl = null,
                        officialVersion = null,
                        sources = emptyList(),
                        defaultSourceId = null,
                    )
                }
                val baseOfficial = BaseSnapshotResolver.isOfficialUrl(archive.url)
                val baseRef = if (baseOfficial) BaseSnapshotResolver.parse(archive.url) else null
                val tip = if (baseOfficial && baseTip != null)
                {
                    baseTip.tip(envRaw, profile = "archive")
                }
                else
                {
                    null
                }
                val officialVersion = when
                {
                    tip != null -> tip.version
                    else -> SnapshotArchiveRef.versionFromUrl(archive.url)
                }
                val official = SnapshotSourceOption(
                    id = PreferCdnSnapshotUseCase.SOURCE_OFFICIAL,
                    label = if (baseOfficial) "Official Base V2" else "Official mirror",
                    url = archive.url,
                    version = if (baseOfficial) baseRef?.flavor else officialVersion,
                    sizeBytes = tip?.sizeBytes ?: archive.sizeBytes,
                    streamUnpack = archive.streamUnpack,
                    available = archive.url.isNotBlank(),
                    detail = when
                    {
                        baseOfficial && baseRef != null ->
                        {
                            val tipNote = tip?.version?.let { " · tip $it" }.orEmpty()
                            "base-reth-node download · ${baseRef.flavor} · manifest ${BaseClusters.SNAPSHOT_API_URL}$tipNote"
                        }
                        officialVersion.isNullOrBlank() ->
                            "Live archive from the network publisher"
                        else ->
                            "Live archive · $officialVersion"
                    },
                )
                val cdn = if (baseOfficial)
                {
                    buildBaseCdnOption(
                        envRaw = envRaw,
                        flavor = baseRef?.flavor ?: type,
                        officialVersion = officialVersion,
                        streamUnpack = archive.streamUnpack,
                        officialSizeBytes = tip?.sizeBytes ?: archive.sizeBytes,
                    )
                }
                else
                {
                    buildCdnOption(
                        networkRaw = networkRaw,
                        envRaw = envRaw,
                        type = type,
                        filename = SnapshotArchiveRef.filenameFromUrl(archive.url),
                        officialVersion = officialVersion,
                        streamUnpack = archive.streamUnpack,
                        officialSizeBytes = archive.sizeBytes,
                    )
                }
                val sources = if (cdn != null) listOf(official, cdn) else listOf(official)
                return SnapshotSourcesResult.Resolved(
                    typeId = type,
                    officialUrl = archive.url,
                    officialVersion = officialVersion,
                    sources = sources,
                    defaultSourceId = defaultSourceId(sources, officialVersion),
                )
            }
        }
    }

    /**
     * Base CDN mirrors only the archive tree; flavor is a download flag on the same manifest.
     */
    private suspend fun buildBaseCdnOption(
        envRaw: String,
        flavor: String,
        officialVersion: String?,
        streamUnpack: Boolean?,
        officialSizeBytes: Long?,
    ): SnapshotSourceOption
    {
        val origin = resolveOrigin()
            ?: return SnapshotSourceOption(
                id = PreferCdnSnapshotUseCase.SOURCE_CDN,
                label = "Snapshot CDN",
                url = null,
                version = null,
                sizeBytes = null,
                streamUnpack = streamUnpack,
                available = false,
                detail = "Snapshot CDN origin is not configured in panel Settings",
            )
        val env = BaseClusters.lookup(envRaw).env
        val f = BaseClusters.normalizeSnapshotFlavor(flavor)
        val versionUrl = "${origin.value}/snapshots/base/$env/archive/VERSION"
        val cdnVersion = probe.versionText(versionUrl)?.trim().orEmpty()
        if (cdnVersion.isEmpty())
        {
            return SnapshotSourceOption(
                id = PreferCdnSnapshotUseCase.SOURCE_CDN,
                label = "Snapshot CDN",
                url = null,
                version = null,
                sizeBytes = null,
                streamUnpack = streamUnpack,
                available = false,
                detail = "CDN mirror not published yet (no VERSION at ${origin.value})",
            )
        }
        val manifestUrl =
            "${origin.value}/snapshots/base/$env/archive/$cdnVersion/manifest.json?env=$env&flavor=$f"
        val present = probe.archivePresent(
            "${origin.value}/snapshots/base/$env/archive/$cdnVersion/manifest.json",
        )
        val matchesOfficial = !officialVersion.isNullOrBlank() && cdnVersion == officialVersion
        val detail = when
        {
            !present ->
                "CDN is syncing $cdnVersion — Base manifest not ready on ${origin.value}"
            matchesOfficial ->
                "Ready on Snapshot CDN · matches official $cdnVersion · --manifest-url · $f"
            !officialVersion.isNullOrBlank() ->
                "Ready on Snapshot CDN · $cdnVersion (official tip is $officialVersion) · $f"
            else ->
                "Ready on Snapshot CDN · $cdnVersion · --manifest-url · $f"
        }
        return SnapshotSourceOption(
            id = PreferCdnSnapshotUseCase.SOURCE_CDN,
            label = "Snapshot CDN",
            url = if (present) manifestUrl else null,
            version = cdnVersion,
            sizeBytes = if (present && matchesOfficial) officialSizeBytes else null,
            streamUnpack = streamUnpack,
            available = present,
            detail = detail,
        )
    }

    private suspend fun buildCdnOption(
        networkRaw: String,
        envRaw: String,
        type: String,
        filename: String?,
        officialVersion: String?,
        streamUnpack: Boolean?,
        officialSizeBytes: Long?,
    ): SnapshotSourceOption?
    {
        val origin = resolveOrigin()
            ?: return SnapshotSourceOption(
                id = PreferCdnSnapshotUseCase.SOURCE_CDN,
                label = "Snapshot CDN",
                url = null,
                version = null,
                sizeBytes = null,
                streamUnpack = streamUnpack,
                available = false,
                detail = "Snapshot CDN origin is not configured in panel Settings",
            )
        if (filename.isNullOrBlank())
        {
            return SnapshotSourceOption(
                id = PreferCdnSnapshotUseCase.SOURCE_CDN,
                label = "Snapshot CDN",
                url = null,
                version = null,
                sizeBytes = null,
                streamUnpack = streamUnpack,
                available = false,
                detail = "Could not derive archive file name from the official URL",
            )
        }
        val network = networkRaw.trim().lowercase()
        val env = envRaw.trim().lowercase()
        val versionUrl = "${origin.value}/snapshots/$network/$env/$type/VERSION"
        val archiveUrl = "${origin.value}/snapshots/$network/$env/$type/$filename"
        val cdnVersion = probe.versionText(versionUrl)?.trim().orEmpty()
        if (cdnVersion.isEmpty())
        {
            return SnapshotSourceOption(
                id = PreferCdnSnapshotUseCase.SOURCE_CDN,
                label = "Snapshot CDN",
                url = null,
                version = null,
                sizeBytes = null,
                streamUnpack = streamUnpack,
                available = false,
                detail = "CDN mirror not published yet (no VERSION at ${origin.value})",
            )
        }
        val present = probe.archivePresent(archiveUrl)
        val matchesOfficial = !officialVersion.isNullOrBlank() && cdnVersion == officialVersion
        val detail = when
        {
            !present ->
                "CDN is syncing $cdnVersion — archive not ready on ${origin.value}"
            matchesOfficial ->
                "Ready on Snapshot CDN · matches official $cdnVersion"
            !officialVersion.isNullOrBlank() ->
                "Ready on Snapshot CDN · $cdnVersion (official tip is $officialVersion)"
            else ->
                "Ready on Snapshot CDN · $cdnVersion"
        }
        return SnapshotSourceOption(
            id = PreferCdnSnapshotUseCase.SOURCE_CDN,
            label = "Snapshot CDN",
            url = if (present) archiveUrl else null,
            version = cdnVersion,
            sizeBytes = if (present && matchesOfficial) officialSizeBytes else null,
            streamUnpack = streamUnpack,
            available = present,
            detail = detail,
        )
    }

    private suspend fun resolveOrigin(): SnapshotCdnOrigin?
    {
        val fromEnv = envSnapshotCdnOrigin?.let { raw ->
            when (val parsed = SnapshotCdnOrigin.parse(raw))
            {
                is SnapshotCdnOrigin.Parse.Ok -> parsed.origin
                else -> null
            }
        }
        if (fromEnv != null)
        {
            return fromEnv
        }
        return store.snapshotCdnOrigin()
    }

    companion object
    {
        fun defaultSourceId(sources: List<SnapshotSourceOption>, officialVersion: String?): String?
        {
            val official = sources.firstOrNull { it.id == PreferCdnSnapshotUseCase.SOURCE_OFFICIAL }
            val cdn = sources.firstOrNull { it.id == PreferCdnSnapshotUseCase.SOURCE_CDN }
            if (
                cdn?.available == true &&
                !officialVersion.isNullOrBlank() &&
                cdn.version == officialVersion
            )
            {
                return PreferCdnSnapshotUseCase.SOURCE_CDN
            }
            if (official?.available == true)
            {
                return PreferCdnSnapshotUseCase.SOURCE_OFFICIAL
            }
            return sources.firstOrNull { it.available }?.id
        }
    }
}
