package rpcnode.toolkit.chains.evmfamily.infrastructure.http

import java.time.Duration
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotSentinel
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttpClients

/**
 * Berachain publishes execution (`reth-*`) and consensus (`beacon-kit-*`) archives of the SAME block in
 * `https://snapshots.berachain.com/index.csv`; a node needs both from one block. The newest block that has both rows
 * wins, and the pair travels in the `evm-snapshot://berachain/<env>/<type>?…` sentinel for the agent's restore script.
 * The index has no env column: its rows are mainnet (the file listed no Bepolia row), so other envs resolve to nothing.
 */
class BerachainSnapshotResolver(
    private val http: SimpleHttp = SimpleHttp(SimpleHttpClients.cio(Duration.ofSeconds(15))),
    private val indexUrl: String = INDEX_URL,
) : SnapshotResolver
{
    override suspend fun resolve(env: EnvId, typeId: String): SnapshotArchive?
    {
        if (env.value != "mainnet") return null
        val type = typeId.trim().lowercase().ifEmpty { "pruned" }
        val pair = pick(parseIndex(http.getText(indexUrl)), type) ?: return null
        val ref = EvmSnapshotRef(
            network = "berachain",
            env = env.value,
            kind = type,
            params = mapOf(
                "block" to pair.block.toString(),
                "el" to pair.execution.url,
                "elsha" to pair.execution.sha256,
                "elbytes" to pair.execution.bytes.toString(),
                "cl" to pair.consensus.url,
                "clsha" to pair.consensus.sha256,
                "clbytes" to pair.consensus.bytes.toString(),
            ),
        )
        return SnapshotArchive(
            url = EvmSnapshotSentinel.url(ref),
            streamUnpack = false,
            sizeBytes = pair.execution.bytes + pair.consensus.bytes,
        )
    }

    data class Row(val type: String, val bytes: Long, val block: Long, val sha256: String, val url: String)

    data class BlockPair(val block: Long, val execution: Row, val consensus: Row)

    companion object
    {
        const val INDEX_URL = "https://snapshots.berachain.com/index.csv"

        /** `type,size_bytes,block_number,version,created_at,sha256,url,url_s3` — rows without a usable `https` url are dropped. */
        fun parseIndex(csv: String?): List<Row>
        {
            if (csv.isNullOrBlank()) return emptyList()
            val lines = csv.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
            val header = lines.firstOrNull()?.split(',')?.map { it.trim() } ?: return emptyList()
            fun col(name: String) = header.indexOf(name)
            val type = col("type")
            val size = col("size_bytes")
            val block = col("block_number")
            val sha = col("sha256")
            val url = col("url")
            if (listOf(type, size, block, sha, url).any { it < 0 }) return emptyList()
            return lines.drop(1).mapNotNull { line ->
                val f = line.split(',')
                if (f.size <= maxOf(type, size, block, sha, url)) return@mapNotNull null
                val u = f[url].trim()
                val b = f[block].trim().toLongOrNull()
                val bytes = f[size].trim().toLongOrNull()
                if (!u.startsWith("https://") || b == null || bytes == null || bytes <= 0) return@mapNotNull null
                Row(f[type].trim(), bytes, b, f[sha].trim().lowercase(), u)
            }
        }

        /** Newest block for which both the `reth-<type>` and the `beacon-kit-<type>` row exist. */
        fun pick(rows: List<Row>, type: String): BlockPair?
        {
            val el = rows.filter { it.type == "reth-$type" }.associateBy { it.block }
            val cl = rows.filter { it.type == "beacon-kit-$type" }.associateBy { it.block }
            val block = el.keys.intersect(cl.keys).maxOrNull() ?: return null
            return BlockPair(block, el.getValue(block), cl.getValue(block))
        }
    }
}
