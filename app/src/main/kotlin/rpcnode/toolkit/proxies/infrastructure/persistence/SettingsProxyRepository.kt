package rpcnode.toolkit.proxies.infrastructure.persistence

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.proxies.domain.model.Proxy
import rpcnode.toolkit.proxies.domain.model.ProxyId
import rpcnode.toolkit.proxies.domain.model.ProxyUrl
import rpcnode.toolkit.proxies.domain.repository.ProxyRepository
import rpcnode.toolkit.settings.infrastructure.persistence.AesGcmSecretBox
import rpcnode.toolkit.settings.infrastructure.persistence.getSetting
import rpcnode.toolkit.settings.infrastructure.persistence.setSetting
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

/** The whole list as one encrypted JSON value in `settings` (URLs carry proxy passwords). */
class SettingsProxyRepository(
    private val db: ToolkitDatabase,
    private val secrets: AesGcmSecretBox,
) : ProxyRepository
{
    private val log = LoggerFactory.getLogger(SettingsProxyRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(ProxyRow.serializer())
    private val lock = Any()

    override suspend fun list(): List<Proxy> = withContext(Dispatchers.IO) { synchronized(lock) { read() } }

    override suspend fun find(id: ProxyId): Proxy? = list().firstOrNull { it.id == id }

    override suspend fun save(proxy: Proxy) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val all = read()
            val next = if (all.any { it.id == proxy.id })
            {
                all.map { if (it.id == proxy.id) proxy else it }
            }
            else
            {
                all + proxy
            }
            write(next)
        }
    }

    override suspend fun delete(id: ProxyId): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val all = read()
            val next = all.filterNot { it.id == id }
            if (next.size != all.size)
            {
                write(next)
            }
            next.size != all.size
        }
    }

    private fun read(): List<Proxy>
    {
        val enc = db.getSetting(KEY).orEmpty().trim()
        if (enc.isEmpty())
        {
            return emptyList()
        }
        val rows = try
        {
            json.decodeFromString(serializer, secrets.decrypt(enc))
        }
        catch (e: Exception)
        {
            log.warn("proxy list unreadable ({}), treating as empty", e.message)
            return emptyList()
        }
        return rows.mapNotNull { row ->
            val id = ProxyId.parse(row.id) ?: return@mapNotNull null
            val url = (ProxyUrl.parse(row.url) as? ProxyUrl.Parse.Ok)?.url ?: return@mapNotNull null
            Proxy(id = id, name = row.name, url = url)
        }
    }

    private fun write(all: List<Proxy>)
    {
        val rows = all.map { ProxyRow(id = it.id.value, name = it.name, url = it.url.value) }
        db.setSetting(KEY, if (rows.isEmpty()) "" else secrets.encrypt(json.encodeToString(serializer, rows)))
    }

    @Serializable
    private data class ProxyRow(val id: String, val name: String = "", val url: String)

    private companion object
    {
        const val KEY = "proxies_enc"
    }
}
