package rpcnode.toolkit.chains.stacks.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.stacks.infrastructure.StacksChains
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive

/**
 * Hiro's chainstate archive (`archive.hiro.so/<env>/stacks-blockchain/<env>-stacks-blockchain-latest.tar.zst`, ~275 GB
 * mainnet), streamed `HTTP → tar -I zstd -x` into the node dir (the agent picks zstd from the `.tar.zst` suffix).
 * The size comes from a one-byte ranged GET: HEAD answers 403 on the signed Cloudflare R2 URL the alias redirects to.
 */
class StacksSnapshotResolver(
    private val size: suspend (url: String) -> Long? = ::rangedSize,
) : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        val e = StacksChains.ALL.firstOrNull { it.id == env.value } ?: return null
        return SnapshotArchive(url = e.snapshotUrl, streamUnpack = true, sizeBytes = size(e.snapshotUrl))
    }

    companion object
    {
        /** `Content-Range: bytes 0-0/<total>` → total. */
        fun totalFromContentRange(header: String?): Long? =
            header?.substringAfter('/', "")?.trim()?.toLongOrNull()

        suspend fun rangedSize(url: String): Long? = withContext(Dispatchers.IO) {
            runCatching {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()
                val req = HttpRequest.newBuilder(URI(url)).header("Range", "bytes=0-0").timeout(Duration.ofSeconds(20)).GET().build()
                val resp = client.send(req, HttpResponse.BodyHandlers.discarding())
                if (resp.statusCode() == 206) totalFromContentRange(resp.headers().firstValue("Content-Range").orElse(null)) else null
            }.getOrNull()
        }
    }
}
