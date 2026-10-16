package rpcnode.toolkit.chains.evmfamily.infrastructure.http

import java.time.Duration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.cometbft.infrastructure.http.CometbftSnapshotResolver
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Kaia's official live-pruned chaindata: `https://snapshots.node.kaia.io/<mainnet|kairos>/pruning-chaindata/latest.txt`
 * holds the URL of the newest `.tar.zst` (2.3 TB on mainnet), `manifest.json` its size. Unpacked in one pass
 * (HTTP → `tar -I zstd`) into the datadir: the archive's top level is `klay/chaindata/`. Archive / full snapshots are no
 * longer published (docs.kaia.io, Sep 2026).
 */
class KaiaSnapshotResolver(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(15))),
    private val base: String = "https://snapshots.node.kaia.io",
) : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        if (env.value != "mainnet" && env.value != "kairos") return null
        val dir = "$base/${env.value}/pruning-chaindata"
        val url = CometbftSnapshotResolver.parseLatestText(http.getText("$dir/latest.txt")) ?: return null
        val bytes = parseBytes(http.getText("$dir/manifest.json"), url) ?: CometbftSnapshotResolver.headLength(url)
        return SnapshotArchive(url = url, streamUnpack = true, sizeBytes = bytes)
    }

    companion object
    {
        private val json = Json { ignoreUnknownKeys = true }

        /** `bytes` of the manifest entry whose `url` is [url] (`latest` in the manifest is only a key string). */
        fun parseBytes(manifest: String?, url: String): Long? =
            runCatching {
                json.parseToJsonElement(manifest ?: return null).jsonObject["snapshots"]?.jsonArray
                    ?.map { it.jsonObject }
                    ?.firstOrNull { it["url"]?.jsonPrimitive?.contentOrNull == url }
                    ?.get("bytes")?.jsonPrimitive?.longOrNull
            }.getOrNull()?.takeIf { it > 0 }
    }
}
