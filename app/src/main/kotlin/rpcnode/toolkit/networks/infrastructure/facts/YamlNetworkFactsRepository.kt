package rpcnode.toolkit.networks.infrastructure.facts

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import rpcnode.toolkit.catalog.domain.Chain
import rpcnode.toolkit.catalog.domain.Env
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.ClientConfigBindingFacts
import rpcnode.toolkit.networks.domain.model.ClientConfigFacts
import rpcnode.toolkit.networks.domain.model.ClientConfigTestConnectFacts
import rpcnode.toolkit.networks.domain.model.NetworkDiskRoleFacts
import rpcnode.toolkit.networks.domain.model.NetworkEnvFacts
import rpcnode.toolkit.networks.domain.model.NetworkFacts
import rpcnode.toolkit.networks.domain.model.SnapshotTypeFacts
import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.shared.infrastructure.classpath.ClasspathChainResources

/**
 * Loads every `chains/<id>/network.yml` on the classpath once at construction — no per-request I/O.
 * The directory name is the network id. That mapping is the catalog; do not also list chains in Kotlin.
 */
class YamlNetworkFactsRepository(
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) : NetworkFactsRepository, NetworkCatalog
{
    private val log = LoggerFactory.getLogger(YamlNetworkFactsRepository::class.java)
    private val byNetwork: Map<String, NetworkFacts> = loadAll(classLoader)
    private val chains: List<Chain> = byNetwork.entries
        .mapNotNull { (id, facts) -> chainFrom(id, facts) }
        .sortedBy { it.id.value }

    override fun factsFor(network: NetworkId): NetworkFacts? = byNetwork[network.value]

    override fun find(id: NetworkId): Chain? = chains.firstOrNull { it.id == id }

    override fun all(): List<Chain> = chains

    private fun loadAll(classLoader: ClassLoader): Map<String, NetworkFacts>
    {
        val out = mutableMapOf<String, NetworkFacts>()
        for (id in ClasspathChainResources.listIdsWith(classLoader, ClasspathChainResources.NETWORK_YML))
        {
            val stream = ClasspathChainResources.open(classLoader, id, ClasspathChainResources.NETWORK_YML)
                ?: continue
            try
            {
                out[id] = stream.use { parseFacts(it.readBytes().decodeToString()) }
            }
            catch (e: Exception)
            {
                log.warn("skipping malformed {}: {}", ClasspathChainResources.path(id, ClasspathChainResources.NETWORK_YML), e.message)
            }
        }
        return out
    }
}
private fun parseFacts(yamlText: String): NetworkFacts
{
    @Suppress("UNCHECKED_CAST")
    val root = (Yaml().load(yamlText) as? Map<String, Any?>) ?: emptyMap()
    return NetworkFacts(
        label = (root["label"] as? String)?.trim()?.ifEmpty { null },
        dataRoot = (root["dataRoot"] as? String)?.trim()?.ifEmpty { null },
        envs = (root["envs"] as? List<*>)?.mapNotNull(::parseEnvFacts) ?: emptyList(),
        diskRoles = (root["diskRoles"] as? List<*>)?.mapNotNull(::parseDiskRoleFacts) ?: emptyList(),
        diskMedia = (root["diskMedia"] as? String)?.trim()?.lowercase()?.ifEmpty { null },
        diskNotes = (root["diskNotes"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
        oneEnvPerHost = root["oneEnvPerHost"] as? Boolean ?: false,
        clientConfig = parseClientConfigFacts(root["clientConfig"]),
    )
}

private fun parseEnvFacts(raw: Any?): NetworkEnvFacts?
{
    val m = raw as? Map<*, *> ?: return null
    val id = (m["id"] as? String)?.trim()?.lowercase().orEmpty()
    if (id.isEmpty()) return null
    return NetworkEnvFacts(
        id = id,
        label = (m["label"] as? String)?.trim()?.ifEmpty { null },
        diskHintGiB = asDouble(m["diskHintGiB"]),
        fullNodeGiB = asDouble(m["fullNodeGiB"]),
        archiveGiB = asDouble(m["archiveGiB"]),
        cpuCores = asDouble(m["cpuCores"]),
        memoryGiB = asDouble(m["memoryGiB"]),
        snapshot = (m["snapshot"] as? String)?.trim()?.lowercase()?.ifEmpty { null },
        snapshotBootstrap = (m["snapshotBootstrap"] as? String)?.trim()?.lowercase()?.ifEmpty { null },
        snapshotTypes = (m["snapshotTypes"] as? List<*>)?.mapNotNull(::parseSnapshotTypeFacts).orEmpty(),
        publicTipUrls = parsePublicTipUrls(m["publicTip"]),
        publicTipBeaconUrls = parsePublicTipBeaconUrls(m["publicTip"]),
        l1RpcUrl = parseL1ParentField(m["l1Parent"], "rpc"),
        l1BeaconUrl = parseL1ParentField(m["l1Parent"], "beacon"),
        l1PickHelp = parseL1ParentField(m["l1Parent"], "pickHelp"),
    )
}

private fun parseL1ParentField(raw: Any?, key: String): String?
{
    val m = raw as? Map<*, *> ?: return null
    return (m[key] as? String)?.trim()?.ifEmpty { null }
}

private fun parsePublicTipUrls(raw: Any?): List<String>
{
    val m = raw as? Map<*, *> ?: return emptyList()
    return (m["urls"] as? List<*>)
        ?.mapNotNull { (it as? String)?.trim()?.takeIf { u -> u.isNotEmpty() } }
        .orEmpty()
}

private fun parsePublicTipBeaconUrls(raw: Any?): List<String>
{
    val m = raw as? Map<*, *> ?: return emptyList()
    val fromList = (m["beaconUrls"] as? List<*>)
        ?.mapNotNull { (it as? String)?.trim()?.takeIf { u -> u.isNotEmpty() } }
        .orEmpty()
    if (fromList.isNotEmpty())
    {
        return fromList
    }
    val single = (m["beacon"] as? String)?.trim()?.ifEmpty { null }
    return if (single != null) listOf(single) else emptyList()
}

private fun parseSnapshotTypeFacts(raw: Any?): SnapshotTypeFacts?
{
    val m = raw as? Map<*, *> ?: return null
    val id = (m["id"] as? String)?.trim()?.lowercase().orEmpty()
    val label = (m["label"] as? String)?.trim().orEmpty()
    if (id.isEmpty() || label.isEmpty())
    {
        return null
    }
    val kind = (m["kind"] as? String)?.trim()?.lowercase()?.ifEmpty { null } ?: id
    val destLeaf = (m["destLeaf"] as? String)?.trim()?.trimStart('/')?.ifEmpty { null }
    return SnapshotTypeFacts(
        id = id,
        kind = kind,
        label = label,
        hint = (m["hint"] as? String)?.trim()?.ifEmpty { null },
        diskGiB = asDouble(m["diskGiB"]),
        default = m["default"] as? Boolean ?: false,
        destLeaf = destLeaf,
    )
}

private fun parseDiskRoleFacts(raw: Any?): NetworkDiskRoleFacts?
{
    val m = raw as? Map<*, *> ?: return null
    val id = (m["id"] as? String)?.trim().orEmpty()
    val label = (m["label"] as? String)?.trim().orEmpty()
    if (id.isEmpty() || label.isEmpty()) return null
    val media = (m["media"] as? String)?.trim()?.lowercase()?.ifEmpty { null } ?: "ssd"
    return NetworkDiskRoleFacts(id = id, label = label, media = media)
}

private fun parseClientConfigFacts(raw: Any?): ClientConfigFacts?
{
    val m = raw as? Map<*, *> ?: return null
    val bindings = (m["bindings"] as? List<*>)?.mapNotNull(::parseClientConfigBinding).orEmpty()
    if (bindings.isEmpty() && (m["program"] as? String).isNullOrBlank())
    {
        return null
    }
    @Suppress("UNCHECKED_CAST")
    val templates = (m["templates"] as? Map<*, *>)
        ?.mapNotNull { (k, v) ->
            val key = (k as? String)?.trim()?.lowercase().orEmpty()
            val file = (v as? String)?.trim().orEmpty()
            if (key.isEmpty() || file.isEmpty()) null else key to file
        }
        ?.toMap()
        .orEmpty()
    @Suppress("UNCHECKED_CAST")
    val envSections = (m["envSections"] as? Map<*, *>)
        ?.mapNotNull { (k, v) ->
            val key = (k as? String)?.trim()?.lowercase().orEmpty()
            val section = (v as? String)?.trim().orEmpty()
            if (key.isEmpty() || section.isEmpty()) null else key to section
        }
        ?.toMap()
        .orEmpty()
    return ClientConfigFacts(
        program = (m["program"] as? String)?.trim().orEmpty(),
        format = (m["format"] as? String)?.trim()?.lowercase().orEmpty(),
        template = (m["template"] as? String)?.trim()?.ifEmpty { null },
        templates = templates,
        envSections = envSections,
        bindings = bindings,
    )
}

private fun parseClientConfigBinding(raw: Any?): ClientConfigBindingFacts?
{
    val m = raw as? Map<*, *> ?: return null
    val path = ((m["path"] as? String) ?: (m["key"] as? String))?.trim().orEmpty()
    val source = (m["source"] as? String)?.trim()?.lowercase().orEmpty()
    if (path.isEmpty() || source.isEmpty()) return null
    return ClientConfigBindingFacts(
        path = path,
        source = source,
        description = (m["description"] as? String)?.trim()?.ifEmpty { null },
        role = (m["role"] as? String)?.trim()?.ifEmpty { null },
        option = (m["option"] as? String)?.trim()?.ifEmpty { null },
        value = (m["value"] as? String)?.trim()?.ifEmpty { null },
        relative = (m["relative"] as? String)?.trim()?.trim('/')?.ifEmpty { null },
        optional = m["optional"] as? Boolean ?: false,
        default = (m["default"] as? String)?.trim()?.ifEmpty { null }
            ?: (m["default"] as? Number)?.toString(),
        map = parseStringMap(m["map"]),
        whenInstallOption = (m["whenInstallOption"] as? String)?.trim()?.ifEmpty { null },
        whenInstallOptionValue = (m["whenInstallOptionValue"] as? String)?.trim()?.ifEmpty { null },
        testConnect = parseTestConnect(m["testConnect"]),
    )
}

private fun parseTestConnect(raw: Any?): ClientConfigTestConnectFacts?
{
    val m = raw as? Map<*, *> ?: return null
    val kind = (m["kind"] as? String)?.trim()?.lowercase().orEmpty()
    if (kind.isEmpty())
    {
        return null
    }
    return ClientConfigTestConnectFacts(
        kind = kind,
        label = (m["label"] as? String)?.trim()?.ifEmpty { null } ?: "Test connect",
        help = (m["help"] as? String)?.trim()?.ifEmpty { null },
    )
}

private fun parseStringMap(raw: Any?): Map<String, String>
{
    val m = raw as? Map<*, *> ?: return emptyMap()
    return m.mapNotNull { (k, v) ->
        val key = (k as? String)?.trim()?.lowercase().orEmpty()
        val value = when (v)
        {
            is String -> v.trim()
            is Number, is Boolean -> v.toString()
            else -> null
        }?.ifEmpty { null }
        if (key.isEmpty() || value == null) null else key to value
    }.toMap()
}

private fun asDouble(v: Any?): Double? = when (v)
{
    null -> null
    is Double -> v
    is Number -> v.toDouble()
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

private fun chainFrom(id: String, facts: NetworkFacts): Chain?
{
    val networkId = NetworkId.parse(id) ?: return null
    val envs = facts.envs.mapNotNull { envFacts ->
        val envId = EnvId.parse(envFacts.id) ?: return@mapNotNull null
        Env(
            id = envId,
            displayName = envFacts.label?.trim()?.ifEmpty { null } ?: envId.value,
        )
    }
    if (envs.isEmpty())
    {
        return null
    }
    return Chain(
        id = networkId,
        label = facts.label?.trim()?.ifEmpty { null } ?: networkId.value,
        dataRoot = facts.dataRoot?.trim().orEmpty(),
        envs = envs,
    )
}
