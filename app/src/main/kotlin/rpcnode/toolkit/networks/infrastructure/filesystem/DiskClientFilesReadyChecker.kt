package rpcnode.toolkit.networks.infrastructure.filesystem

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.application.ClientFilesReadyChecker

/** Checks `<destDir>/<network>/<env>/{manifest.json,VERSION}`. */
class DiskClientFilesReadyChecker(
    private val destDir: Path,
) : ClientFilesReadyChecker
{
    override fun ready(network: NetworkId, envs: List<EnvId>): Boolean
    {
        val networkSeg = ClientDestPaths.safeSegment(network.value) ?: return false
        return envs.any { env ->
            val envSeg = ClientDestPaths.safeSegment(env.value) ?: return@any false
            val dir = destDir.resolve(networkSeg).resolve(envSeg)
            Files.isRegularFile(dir.resolve("manifest.json")) || Files.isRegularFile(dir.resolve("VERSION"))
        }
    }
}
