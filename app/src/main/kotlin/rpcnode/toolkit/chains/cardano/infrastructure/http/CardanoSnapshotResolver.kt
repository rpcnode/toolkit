package rpcnode.toolkit.chains.cardano.infrastructure.http

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoClusters
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

/**
 * Mithril snapshots are restored by `mithril-client`, not toolkit aria2. Resolves to a `mithril://<env>` sentinel;
 * the host agent runs [CardanoMithrilSnapshotRunner].
 */
class CardanoSnapshotResolver : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        if (env != EnvId.MAINNET && env != EnvId.PREPROD && env != EnvId.PREVIEW)
        {
            return null
        }
        return SnapshotArchive(url = "$SCHEME://${CardanoClusters.normalizeEnv(env.value)}", streamUnpack = false, sizeBytes = null)
    }

    companion object
    {
        const val SCHEME = "mithril"

        fun isOfficialUrl(url: String): Boolean = url.trim().lowercase().startsWith("$SCHEME://")

        fun parse(url: String): OfficialRef?
        {
            val raw = url.trim()
            if (!isOfficialUrl(raw))
            {
                return null
            }
            val envPart = raw.substringAfter("://").substringBefore('?').trim('/').split('/').firstOrNull().orEmpty()
            return OfficialRef(env = CardanoClusters.normalizeEnv(envPart))
        }
    }

    data class OfficialRef(val env: String)
}
