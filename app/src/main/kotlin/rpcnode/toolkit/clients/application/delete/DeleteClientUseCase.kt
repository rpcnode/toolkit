package rpcnode.toolkit.clients.application.delete

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.networks.infrastructure.filesystem.ClientDestPaths

sealed interface DeleteClientResult
{
    data class Ok(val purged: Boolean, val dest: String, val removed: List<String>) : DeleteClientResult
    data class Failed(val error: String) : DeleteClientResult
}

/** Wipes the on-disk dest tree and the DB pin(s). A network-wide delete (no env) marks the network purged. */
class DeleteClientUseCase(
    private val versionRepository: ClientVersionRepository,
    private val destDir: Path,
)
{
    suspend operator fun invoke(networkRaw: String, envRaw: String?): DeleteClientResult = withContext(Dispatchers.IO) {
        val networkSeg = ClientDestPaths.safeSegment(networkRaw)
        val networkId = networkSeg?.let { NetworkId.parse(it) }
        if (networkSeg == null || networkId == null)
        {
            return@withContext DeleteClientResult.Failed("network required")
        }

        val removed = mutableListOf<String>()
        if (envRaw != null)
        {
            val envSeg = ClientDestPaths.safeSegment(envRaw)
            val envId = envSeg?.let { EnvId.parse(it) }
            if (envSeg == null || envId == null)
            {
                return@withContext DeleteClientResult.Failed("bad env")
            }
            val dir = destDir.resolve(networkSeg).resolve(envSeg)
            if (removeTree(dir)) removed += dir.toString()
            versionRepository.deleteEnv(networkId, envId)
            return@withContext DeleteClientResult.Ok(purged = false, dest = destDir.toString(), removed = removed)
        }

        val dir = destDir.resolve(networkSeg)
        if (removeTree(dir)) removed += dir.toString()
        versionRepository.deleteNetwork(networkId)
        versionRepository.markPurged(networkId)
        DeleteClientResult.Ok(purged = true, dest = destDir.toString(), removed = removed)
    }

    private fun removeTree(dir: Path): Boolean
    {
        if (!Files.exists(dir))
        {
            return false
        }
        Files.walk(dir).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
        return true
    }
}
