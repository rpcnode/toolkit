package rpcnode.toolkit.agent.infrastructure.enroll

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rpcnode.toolkit.agent.application.enroll.PanelEnrollmentStore
import rpcnode.toolkit.agent.domain.model.PanelEnrollment

class InMemoryPanelEnrollmentStore : PanelEnrollmentStore
{
    @Volatile
    private var value: PanelEnrollment? = null

    override suspend fun read(): PanelEnrollment? = value

    override suspend fun write(enrollment: PanelEnrollment)
    {
        value = enrollment
    }

    override suspend fun clear()
    {
        value = null
    }
}

class FilePanelEnrollmentStore(
    private val file: Path,
) : PanelEnrollmentStore
{
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun read(): PanelEnrollment? = withContext(Dispatchers.IO) {
        if (!Files.isRegularFile(file))
        {
            return@withContext null
        }
        try
        {
            json.decodeFromString<StoredEnrollment>(Files.readString(file)).toDomain()
        }
        catch (_: Exception)
        {
            null
        }
    }

    override suspend fun write(enrollment: PanelEnrollment) = withContext(Dispatchers.IO) {
        Files.createDirectories(file.parent)
        Files.writeString(file, json.encodeToString(enrollment.toStored()))
        Unit
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        Files.deleteIfExists(file)
        Unit
    }

    private fun StoredEnrollment.toDomain() = PanelEnrollment(
        panelUrl = panelUrl,
        serverId = serverId,
        ingestPath = ingestPath.ifBlank { PanelEnrollment.DEFAULT_INGEST_PATH },
    )

    private fun PanelEnrollment.toStored() = StoredEnrollment(
        panelUrl = panelUrl,
        serverId = serverId,
        ingestPath = ingestPath,
    )
}

@Serializable
private data class StoredEnrollment(
    @SerialName("panel_url") val panelUrl: String,
    @SerialName("server_id") val serverId: String,
    @SerialName("ingest_path") val ingestPath: String = PanelEnrollment.DEFAULT_INGEST_PATH,
)
