package rpcnode.toolkit.networks.infrastructure.catalog

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.SnapshotMirrorSpec
import rpcnode.toolkit.networks.domain.repository.SnapshotMirrorCatalog
import rpcnode.toolkit.shared.infrastructure.classpath.ClasspathChainResources

/**
 * Official snapshot mirrors from `chains/<id>/clients.yml` → `snapshots:`.
 */
class YamlSnapshotMirrorCatalog(
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) : SnapshotMirrorCatalog
{
    private val log = LoggerFactory.getLogger(YamlSnapshotMirrorCatalog::class.java)
    private val byKey: Map<Triple<NetworkId, EnvId, String>, SnapshotMirrorSpec> = loadAll(classLoader)

    override fun mirror(network: NetworkId, env: EnvId, typeId: String): SnapshotMirrorSpec? =
        byKey[Triple(network, env, typeId.trim().lowercase())]

    override fun typesFor(network: NetworkId, env: EnvId): List<SnapshotMirrorSpec> =
        byKey.values.filter { it.network == network && it.env == env }.sortedBy { it.typeId }

    private fun loadAll(classLoader: ClassLoader): Map<Triple<NetworkId, EnvId, String>, SnapshotMirrorSpec>
    {
        val out = linkedMapOf<Triple<NetworkId, EnvId, String>, SnapshotMirrorSpec>()
        for (id in ClasspathChainResources.listIdsWith(classLoader, ClasspathChainResources.CLIENTS_YML))
        {
            val network = NetworkId.parse(id) ?: continue
            val stream = ClasspathChainResources.open(classLoader, id, ClasspathChainResources.CLIENTS_YML)
                ?: continue
            try
            {
                for (spec in stream.use { parseSnapshots(network, it.readBytes().decodeToString()) })
                {
                    out[Triple(spec.network, spec.env, spec.typeId)] = spec
                }
            }
            catch (e: Exception)
            {
                log.warn(
                    "skipping snapshot mirrors in {}: {}",
                    ClasspathChainResources.path(id, ClasspathChainResources.CLIENTS_YML),
                    e.message,
                )
            }
        }
        return out
    }
}
@Suppress("UNCHECKED_CAST")
private fun parseSnapshots(network: NetworkId, yamlText: String): List<SnapshotMirrorSpec>
{
    val root = (Yaml().load(yamlText) as? Map<String, Any?>) ?: return emptyList()
    val snapshots = root["snapshots"] as? Map<*, *> ?: return emptyList()
    val out = mutableListOf<SnapshotMirrorSpec>()
    for ((envRaw, typesRaw) in snapshots)
    {
        val env = EnvId.parse((envRaw as? String)?.trim().orEmpty()) ?: continue
        val types = typesRaw as? Map<*, *> ?: continue
        for ((typeRaw, bodyRaw) in types)
        {
            val typeId = (typeRaw as? String)?.trim()?.lowercase().orEmpty()
            val body = bodyRaw as? Map<*, *> ?: continue
            val mirror = (body["mirror"] as? String)?.trim().orEmpty()
            val filename = (body["filename"] as? String)?.trim().orEmpty()
            if (typeId.isEmpty() || mirror.isEmpty() || filename.isEmpty())
            {
                continue
            }
            val discover = (body["discover"] as? String)?.trim()?.lowercase()?.ifEmpty { null } ?: "listing"
            out += SnapshotMirrorSpec(
                network = network,
                env = env,
                typeId = typeId,
                mirror = mirror,
                filename = filename,
                discover = discover,
            )
        }
    }
    return out
}
