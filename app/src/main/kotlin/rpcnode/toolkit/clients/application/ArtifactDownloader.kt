package rpcnode.toolkit.clients.application

import java.nio.file.Path

/** Streams one URL to disk, reporting progress as bytes arrive. */
fun interface ArtifactDownloader
{
    suspend fun download(url: String, dest: Path, onProgress: (bytesRead: Long, totalBytes: Long) -> Unit)
}
