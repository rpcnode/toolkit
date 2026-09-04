package rpcnode.toolkit.settings.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rpcnode.toolkit.settings.application.get.InstallStamp
import rpcnode.toolkit.settings.application.get.InstallStampWriter

class FileInstallStampWriter(private val path: Path) : InstallStampWriter
{
    private val json = Json { encodeDefaults = true }

    override fun write(stamp: InstallStamp)
    {
        val parent = path.parent
        if (parent != null)
        {
            Files.createDirectories(parent)
        }
        val body = json.encodeToString(
            StampFile.serializer(),
            StampFile(
                version = stamp.version,
                installedAt = stamp.installedAt,
                updatedAt = stamp.updatedAt,
            ),
        ) + "\n"
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try
        {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        catch (_: Exception)
        {
            Files.writeString(path, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.deleteIfExists(tmp)
        }
    }

    @Serializable
    private data class StampFile(
        val version: String = "",
        @SerialName("installed_at") val installedAt: String = "",
        @SerialName("updated_at") val updatedAt: String = "",
    )
}
