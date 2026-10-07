package rpcnode.toolkit.agent.infrastructure.catalog

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import rpcnode.toolkit.shared.infrastructure.classpath.ClasspathChainResources

/**
 * Every TCP port the shipped `chains/<id>/clients.yml` catalog fixes for some network/env — the
 * same files the panel's `YamlClientProgramCatalog` reads, but only the `ports:` numbers. The
 * agent must reserve these out of the kernel ephemeral allocator; it has no reason to know
 * artifacts, sources or download URLs, so it does not pull in the panel's client catalog types.
 */
class CatalogFixedPortsReader(
    private val classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
)
{
    private val log = LoggerFactory.getLogger(CatalogFixedPortsReader::class.java)

    fun read(): List<Int>
    {
        val ports = mutableSetOf<Int>()
        for (id in ClasspathChainResources.listIdsWith(classLoader, ClasspathChainResources.CLIENTS_YML))
        {
            val stream = ClasspathChainResources.open(classLoader, id, ClasspathChainResources.CLIENTS_YML)
                ?: continue
            runCatching { stream.use { extractPorts(it.readBytes().decodeToString()) } }
                .onSuccess { ports += it }
                .onFailure {
                    log.warn(
                        "skipping malformed {}: {}",
                        ClasspathChainResources.path(id, ClasspathChainResources.CLIENTS_YML),
                        it.message,
                    )
                }
        }
        return ports.sorted()
    }
}

private fun extractPorts(yamlText: String): List<Int>
{
    @Suppress("UNCHECKED_CAST")
    val root = (Yaml().load(yamlText) as? Map<String, Any?>) ?: emptyMap()
    val programs = root["programs"] as? List<*> ?: return emptyList()
    return programs.flatMap { program ->
        val m = program as? Map<*, *> ?: return@flatMap emptyList()
        val rawPorts = m["ports"] as? List<*> ?: return@flatMap emptyList()
        rawPorts.mapNotNull { entry ->
            val pm = entry as? Map<*, *> ?: return@mapNotNull null
            val port = (pm["port"] as? Int) ?: (pm["port"] as? String)?.trim()?.toIntOrNull()
            port?.takeIf { it in 1..65_535 }
        }
    }
}
