package rpcnode.toolkit.cdn.infrastructure.filesystem

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import rpcnode.toolkit.cdn.application.sync.SnapshotTarget
import rpcnode.toolkit.cdn.application.targets.SnapshotTargetStore

/** JSON list of `network/env/type` targets the CDN should mirror. */
class FileSnapshotTargetStore(
    private val path: Path,
) : SnapshotTargetStore
{
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override fun list(): List<SnapshotTarget>
    {
        if (!Files.isRegularFile(path))
        {
            return emptyList()
        }
        return try
        {
            val dto = json.decodeFromString<TargetsDto>(Files.readString(path))
            dto.targets.mapNotNull { row ->
                val network = row.network.trim().lowercase().ifEmpty { return@mapNotNull null }
                val env = row.env.trim().lowercase().ifEmpty { return@mapNotNull null }
                val type = row.type.trim().lowercase().ifEmpty { "full" }
                SnapshotTarget(network, env, type)
            }.distinctBy { it.id }
        }
        catch (_: Exception)
        {
            emptyList()
        }
    }

    override fun add(target: SnapshotTarget)
    {
        val next = (list() + target.normalized()).distinctBy { it.id }
        write(next)
    }

    override fun remove(id: String): Boolean
    {
        val before = list()
        val after = before.filterNot { it.id == id }
        if (after.size == before.size)
        {
            return false
        }
        write(after)
        return true
    }

    private fun write(targets: List<SnapshotTarget>)
    {
        val parent = path.parent
        if (parent != null)
        {
            Files.createDirectories(parent)
        }
        val dto = TargetsDto(
            targets = targets.map {
                TargetDto(network = it.network, env = it.env, type = it.type)
            },
        )
        Files.writeString(path, json.encodeToString(dto) + "\n")
    }

    private fun SnapshotTarget.normalized() = SnapshotTarget(
        network = network.trim().lowercase(),
        env = env.trim().lowercase(),
        type = type.trim().lowercase().ifEmpty { "full" },
    )

    @Serializable
    private data class TargetsDto(
        val targets: List<TargetDto> = emptyList(),
    )

    @Serializable
    private data class TargetDto(
        val network: String,
        val env: String,
        val type: String = "full",
    )
}
