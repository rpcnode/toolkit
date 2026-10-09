package rpcnode.toolkit.clients.application

import java.nio.file.Path

data class ClientManifestFileEntry(
    val name: String,
    val role: String,
    val url: String,
)

/** Writes `manifest.json` + `VERSION` into a client dest dir — exactly what `DiskClientFilesReadyChecker` looks for. */
interface ClientManifestWriter
{
    suspend fun write(
        dir: Path,
        network: String,
        env: String,
        program: String,
        version: String,
        tag: String,
        source: String,
        notes: String,
        files: List<ClientManifestFileEntry>,
    )
}
