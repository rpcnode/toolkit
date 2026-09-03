package rpcnode.toolkit.chains.sui.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.sui.infrastructure.SuiClusters
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

/**
 * Mysten formal R2 snapshots are downloaded by `sui-tool`, not toolkit aria2.
 * Resolves to a `formal-r2://` sentinel; the host agent runs [SuiFormalSnapshotRunner].
 */
class SuiSnapshotResolver : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val cluster = SuiClusters.lookup(env.value)
        if (cluster.env != "mainnet" && cluster.env != "testnet")
        {
            return null
        }
        return SnapshotArchive(
            url = "$SCHEME://${cluster.env}",
            streamUnpack = false,
            sizeBytes = null,
        )
    }

    companion object
    {
        const val SCHEME = "formal-r2"

        fun isOfficialUrl(url: String): Boolean =
            url.trim().lowercase().startsWith("$SCHEME://")

        fun parse(url: String): OfficialRef?
        {
            val raw = url.trim()
            if (!isOfficialUrl(raw))
            {
                return null
            }
            val rest = raw.substringAfter("://").substringBefore('?').trim('/')
            val envPart = rest.split('/').firstOrNull().orEmpty()
            val cluster = SuiClusters.lookup(envPart)
            return OfficialRef(env = cluster.env)
        }
    }

    data class OfficialRef(
        val env: String,
    )
}
