package rpcnode.toolkit.chains.starknet.infrastructure.http

import java.net.URI
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetChain
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.application.snapshot.SnapshotSizeProbe
import rpcnode.toolkit.networks.domain.model.SnapshotArchive
import rpcnode.toolkit.networks.domain.repository.SnapshotMirrorCatalog
import rpcnode.toolkit.networks.infrastructure.http.HttpSnapshotSizeProbe

/**
 * Official Juno snapshots (`clients/starknet` → `snapshots:`). `https://juno-snapshots.nethermind.io/files/<name>/latest`
 * answers 200 with the newest dated `tar.zst` (it does not redirect; the dated file name is not downloadable on its own and
 * `Accept-Ranges: bytes` is honoured), so that URL is the archive URL. The agent streams it HTTP → `zstd -d` → `tar -x`
 * into the Juno DB directory; [isOfficialUrl] tells it the stream is zstd although the URL carries no `.zst` suffix.
 */
class StarknetSnapshotResolver(
    private val mirrors: SnapshotMirrorCatalog,
    private val sizeProbe: SnapshotSizeProbe = HttpSnapshotSizeProbe(),
) : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val type = StarknetChain.normalizeSnapshot(typeId)
        val spec = mirrors.mirror(NetworkId.STARKNET, env, type) ?: return null
        val url = spec.mirror.trim()
        if (url.isEmpty())
        {
            return null
        }
        return SnapshotArchive(url = url, streamUnpack = true, sizeBytes = sizeProbe.bytes(url))
    }

    companion object
    {
        const val HOST = "juno-snapshots.nethermind.io"

        /** `https://juno-snapshots.nethermind.io/files/<name>/latest` (the stream behind it is tar.zst). */
        fun isOfficialUrl(url: String): Boolean
        {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            val path = uri.path.orEmpty().trimEnd('/')
            return uri.host.equals(HOST, ignoreCase = true) && path.startsWith("/files/") && path.endsWith("/latest")
        }
    }
}
