package rpcnode.toolkit.settings.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rpcnode.toolkit.settings.application.get.InstallStamp
import rpcnode.toolkit.settings.application.get.InstallStampReader

class FileInstallStampReader(private val path: Path) : InstallStampReader
{
    private val json = Json { ignoreUnknownKeys = true }

    override fun read(): InstallStamp?
    {
        if (!Files.isRegularFile(path))
        {
            return null
        }
        val raw = Files.readString(path).trim()
        if (raw.isEmpty())
        {
            return null
        }
        val rec = runCatching { json.decodeFromString(StampFile.serializer(), raw) }.getOrNull() ?: return null
        if (rec.version.isBlank())
        {
            return null
        }
        return InstallStamp(
            version = rec.version,
            installedAt = rec.installedAt,
            updatedAt = rec.updatedAt,
        )
    }

    @Serializable
    private data class StampFile(
        val version: String = "",
        @SerialName("installed_at") val installedAt: String = "",
        @SerialName("updated_at") val updatedAt: String = "",
    )
}
