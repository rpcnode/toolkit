package rpcnode.toolkit.clients.infrastructure.filesystem

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rpcnode.toolkit.clients.application.ClientManifestFileEntry
import rpcnode.toolkit.clients.application.ClientManifestWriter

@Serializable
private data class ManifestFileDto(val name: String, val role: String, val url: String)

@Serializable
private data class ManifestProgramDto(
    val version: String = "",
    val tag: String = "",
    val source: String = "",
    val notes: String = "",
    val files: List<ManifestFileDto> = emptyList(),
)

@Serializable
private data class ManifestDto(
    val network: String,
    val env: String,
    @SerialName("fetched_at") val fetchedAt: String,
    val programs: MutableMap<String, ManifestProgramDto> = mutableMapOf(),
)

private val manifestJson = Json { ignoreUnknownKeys = true; prettyPrint = true }

/** One `manifest.json` per `<network>/<env>` dir, merging in per-program entries so sibling programs can share a dest. */
class FileClientManifestWriter : ClientManifestWriter
{
    override suspend fun write(
        dir: Path,
        network: String,
        env: String,
        program: String,
        version: String,
        tag: String,
        source: String,
        notes: String,
        files: List<ClientManifestFileEntry>,
    ) = withContext(Dispatchers.IO) {
        Files.createDirectories(dir)
        val manifestPath = dir.resolve("manifest.json")
        val existing = if (Files.isRegularFile(manifestPath))
        {
            runCatching { manifestJson.decodeFromString<ManifestDto>(Files.readString(manifestPath)) }.getOrNull()
        }
        else
        {
            null
        }
        val manifest = existing ?: ManifestDto(network = network, env = env, fetchedAt = Instant.now().toString())
        manifest.programs[program] = ManifestProgramDto(
            version = version,
            tag = tag,
            source = source,
            notes = notes,
            files = files.map { ManifestFileDto(it.name, it.role, it.url) },
        )
        Files.writeString(manifestPath, manifestJson.encodeToString(manifest.copy(fetchedAt = Instant.now().toString())))
        Files.writeString(dir.resolve("VERSION"), version.ifBlank { "unknown" } + "\n")
        Unit
    }
}
