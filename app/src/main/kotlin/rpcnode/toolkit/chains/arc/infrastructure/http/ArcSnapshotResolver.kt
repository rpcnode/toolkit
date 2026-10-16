package rpcnode.toolkit.chains.arc.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.arc.infrastructure.ArcClusters
import rpcnode.toolkit.chains.arc.infrastructure.ArcNodeMode
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

/**
 * Arc snapshots are restored by the node's own `arc-snapshots` tool (execution + consensus layers), not by the toolkit
 * downloader. Resolves to an `arc-snapshots://<env>/<type>` sentinel; the host agent runs [ArcSnapshotRunner].
 */
class ArcSnapshotResolver : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val cluster = ArcClusters.lookup(env.value)
        return SnapshotArchive(
            url = "$SCHEME://${cluster.env}/${ArcNodeMode.normalize(typeId)}",
            streamUnpack = false,
            sizeBytes = null,
        )
    }

    data class Ref(val env: String, val kind: String)

    companion object
    {
        const val SCHEME = "arc-snapshots"

        fun isOfficialUrl(url: String): Boolean = url.trim().lowercase().startsWith("$SCHEME://")

        fun parse(url: String): Ref?
        {
            if (!isOfficialUrl(url))
            {
                return null
            }
            val rest = url.trim().substring(SCHEME.length + 3)
            val env = rest.substringBefore('/').lowercase()
            val kind = rest.substringAfter('/', "full")
            return Ref(ArcClusters.normalizeEnv(env), ArcNodeMode.normalize(kind))
        }
    }
}
