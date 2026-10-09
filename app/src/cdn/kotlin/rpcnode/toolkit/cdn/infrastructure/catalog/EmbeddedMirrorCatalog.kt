package rpcnode.toolkit.cdn.infrastructure.catalog

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import rpcnode.toolkit.cdn.domain.model.MirrorSpec

/** Ships known official mirrors (`cdn/mirrors.json`). */
class EmbeddedMirrorCatalog(
    private val classLoader: ClassLoader = EmbeddedMirrorCatalog::class.java.classLoader,
)
{
    private val json = Json { ignoreUnknownKeys = true }
    private val items: List<MirrorSpec> = load()

    fun all(): List<MirrorSpec> = items

    fun find(network: String, env: String, type: String): MirrorSpec? =
        items.firstOrNull {
            it.network == network.trim().lowercase() &&
                it.env == env.trim().lowercase() &&
                it.type == type.trim().lowercase()
        }

    fun networks(): List<String> = items.map { it.network }.distinct()

    fun envs(network: String): List<String> =
        items.filter { it.network == network.trim().lowercase() }.map { it.env }.distinct()

    fun types(network: String, env: String): List<String> =
        items.filter {
            it.network == network.trim().lowercase() && it.env == env.trim().lowercase()
        }.map { it.type }.distinct()

    private fun load(): List<MirrorSpec>
    {
        val stream = classLoader.getResourceAsStream("cdn/mirrors.json")
            ?: error("missing resource cdn/mirrors.json")
        val text = stream.bufferedReader().use { it.readText() }
        val dto = json.decodeFromString<MirrorsDto>(text)
        return dto.items.map {
            MirrorSpec(
                network = it.network.trim().lowercase(),
                env = it.env.trim().lowercase(),
                type = it.type.trim().lowercase().ifEmpty { "full" },
                mirror = it.mirror.trim(),
                filename = it.filename.trim(),
                discover = it.discover.trim().lowercase().ifEmpty { "listing" },
            )
        }
    }

    @Serializable
    private data class MirrorsDto(
        val items: List<MirrorItemDto> = emptyList(),
    )

    @Serializable
    private data class MirrorItemDto(
        val network: String,
        val env: String,
        val type: String = "full",
        val mirror: String,
        val filename: String,
        val discover: String = "listing",
    )
}
