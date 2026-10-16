package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.file.Path

/**
 * A scripted snapshot restore (client CLI download, genesis import, snapshot pair): the panel resolves it to an
 * `evm-snapshot://<network>/<env>/<kind>?k=v` URL, the host agent runs the network's script for it (EvmSnapshotRunner).
 * [params] carry what the resolver learned from the network (for example the two Berachain archives of one block).
 */
data class EvmSnapshotRef(
    val network: String,
    val env: String,
    val kind: String,
    val params: Map<String, String> = emptyMap(),
)
{
    fun param(key: String): String = params[key].orEmpty()
}

/** Where a scripted snapshot works: the dest dir (disk role leaf = node_dir), its installed binaries, state files. */
data class EvmSnapshotPaths(
    val root: Path,
    val bins: Map<String, Path>,
    val marker: Path,
    val state: Path,
    val logFile: Path,
    val script: Path,
)

object EvmSnapshotSentinel
{
    const val SCHEME = "evm-snapshot"

    fun isSentinel(url: String): Boolean = url.trim().lowercase().startsWith("$SCHEME://")

    fun url(ref: EvmSnapshotRef): String
    {
        val base = "$SCHEME://${ref.network}/${ref.env}/${ref.kind}"
        if (ref.params.isEmpty()) return base
        return base + "?" + ref.params.entries.sortedBy { it.key }
            .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
    }

    fun parse(url: String): EvmSnapshotRef?
    {
        if (!isSentinel(url)) return null
        val rest = url.trim().substring(SCHEME.length + 3)
        val path = rest.substringBefore('?')
        val parts = path.split('/')
        if (parts.size != 3 || parts.any { it.isBlank() }) return null
        val params = rest.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
        return EvmSnapshotRef(parts[0].lowercase(), parts[1].lowercase(), parts[2].lowercase(), params)
    }

    /** [sentinel] with [extra] added to its parameters (blank values dropped); a URL that is not a sentinel stays as it is. */
    fun withParams(sentinel: String, extra: Map<String, String>): String
    {
        val ref = parse(sentinel) ?: return sentinel
        val add = extra.filterValues { it.isNotBlank() }
        if (add.isEmpty()) return sentinel
        return url(ref.copy(params = ref.params + add))
    }

    /** Marker of a finished restore, written by the script; the unit never starts it again. */
    fun marker(root: Path): Path = root.resolve(".toolkit").resolve("evm-snapshot.done")
}
