package rpcnode.toolkit.chains.arb.infrastructure.docker

import java.nio.file.Path
import rpcnode.toolkit.clients.application.ArtifactDownloader

/**
 * Downloads `docker://offchainlabs/nitro-node:{tag}` by pulling image layers from Docker Hub
 * Registry HTTP (no Docker daemon) and packing a client tarball; other URLs go to [http].
 */
class ArbAwareArtifactDownloader(
    private val http: ArtifactDownloader,
) : ArtifactDownloader
{
    override suspend fun download(
        url: String,
        dest: Path,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit,
    )
    {
        val u = url.trim()
        val prefix = "docker://${ArbNitroDockerTags.IMAGE}:"
        if (u.startsWith(prefix, ignoreCase = true))
        {
            val tag = u.removePrefix(prefix).trim()
            ArbNitroDockerPack.pack(ArbNitroDockerTags.imageRef(tag), dest, onProgress)
            return
        }
        http.download(url, dest, onProgress)
    }
}

