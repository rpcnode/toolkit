package rpcnode.toolkit.clients.infrastructure.catalog

import org.slf4j.LoggerFactory
import org.yaml.snakeyaml.Yaml
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramRequirements
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.domain.model.PortConfigPolicy
import rpcnode.toolkit.clients.domain.model.ProgramPort
import rpcnode.toolkit.clients.domain.repository.ClientProgramCatalog
import rpcnode.toolkit.shared.infrastructure.classpath.ClasspathChainResources

/**
 * Loads every `chains/<id>/clients.yml` on the classpath once at construction — no per-request I/O.
 * A network with no CDN client (no file) simply returns an empty program list.
 */
class YamlClientProgramCatalog(
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) : ClientProgramCatalog
{
    private val log = LoggerFactory.getLogger(YamlClientProgramCatalog::class.java)
    private val programs: List<ClientProgramSpec> = loadAll(classLoader)
    private val byNetworkEnv: Map<Pair<NetworkId, EnvId>, List<ClientProgramSpec>> =
        programs.groupBy { it.network to it.env }

    override fun programsFor(network: NetworkId, env: EnvId): List<ClientProgramSpec> =
        byNetworkEnv[network to env].orEmpty()

    override fun all(): List<ClientProgramSpec> = programs

    private fun loadAll(classLoader: ClassLoader): List<ClientProgramSpec>
    {
        val out = mutableListOf<ClientProgramSpec>()
        for (id in ClasspathChainResources.listIdsWith(classLoader, ClasspathChainResources.CLIENTS_YML))
        {
            val network = NetworkId.parse(id) ?: continue
            val stream = ClasspathChainResources.open(classLoader, id, ClasspathChainResources.CLIENTS_YML)
                ?: continue
            try
            {
                out += stream.use { parsePrograms(network, it.readBytes().decodeToString()) }
            }
            catch (e: Exception)
            {
                log.warn(
                    "skipping malformed {}: {}",
                    ClasspathChainResources.path(id, ClasspathChainResources.CLIENTS_YML),
                    e.message,
                )
            }
        }
        return out
    }
}
private fun parsePrograms(network: NetworkId, yamlText: String): List<ClientProgramSpec>
{
    @Suppress("UNCHECKED_CAST")
    val root = (Yaml().load(yamlText) as? Map<String, Any?>) ?: emptyMap()
    val rawPrograms = root["programs"] as? List<*> ?: emptyList<Any?>()
    return rawPrograms.mapNotNull { parseProgram(network, it) }
}

private fun parseProgram(network: NetworkId, raw: Any?): ClientProgramSpec?
{
    val m = raw as? Map<*, *> ?: return null
    val env = EnvId.parse((m["env"] as? String).orEmpty()) ?: return null
    val id = (m["id"] as? String)?.trim().orEmpty()
    if (id.isEmpty()) return null
    val source = parseSource(m["source"]) ?: return null
    return ClientProgramSpec(
        network = network,
        env = env,
        programId = id,
        source = source,
        artifacts = parseArtifacts(m["artifacts"], ClientArtifactRole.ARTIFACT),
        configs = parseArtifacts(m["configs"], ClientArtifactRole.CONFIG),
        ports = parsePorts(m["ports"]),
        requirements = parseRequirements(m["requirements"]),
        skipReason = (m["skipReason"] as? String)?.trim()?.ifEmpty { null },
    )
}

private fun parseRequirements(raw: Any?): ClientProgramRequirements
{
    val m = raw as? Map<*, *> ?: return ClientProgramRequirements()
    val javaMajor = (m["javaMajor"] as? Int)
        ?: (m["java_major"] as? Int)
        ?: (m["javaMajor"] as? String)?.trim()?.toIntOrNull()
        ?: (m["java_major"] as? String)?.trim()?.toIntOrNull()
    val logFile = ((m["logFile"] as? String) ?: (m["log_file"] as? String))
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.startsWith("/") && ".." !in it.split('/', '\\') }
    return ClientProgramRequirements(
        javaMajor = javaMajor?.takeIf { it in 1..99 },
        logFile = logFile,
    )
}

private fun parsePorts(raw: Any?): List<ProgramPort>
{
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { entry ->
        val m = entry as? Map<*, *> ?: return@mapNotNull null
        val role = (m["role"] as? String)?.trim().orEmpty()
        val port = (m["port"] as? Int) ?: (m["port"] as? String)?.trim()?.toIntOrNull()
        if (role.isEmpty() || port == null || port !in 1..65_535) return@mapNotNull null
        ProgramPort(
            role = role,
            port = port,
            label = (m["label"] as? String)?.trim().orEmpty(),
            configPolicy = PortConfigPolicy.parse(m["config"] as? String),
        )
    }
}

private fun parseSource(raw: Any?): ClientVersionSource?
{
    val m = raw as? Map<*, *> ?: return null
    return when ((m["type"] as? String)?.trim()?.lowercase())
    {
        "github" ->
        {
            val repo = (m["repo"] as? String)?.trim().orEmpty()
            if (repo.isEmpty()) return null
            ClientVersionSource.GitHubRelease(
                repo = repo,
                tagPrefix = (m["tagPrefix"] as? String)?.trim()?.ifEmpty { null },
            )
        }
        "pinned" ->
        {
            val version = (m["version"] as? String)?.trim().orEmpty()
            val tag = (m["tag"] as? String)?.trim().orEmpty()
            if (version.isEmpty()) return null
            ClientVersionSource.Pinned(
                version = version,
                tag = tag,
                label = (m["label"] as? String)?.trim().orEmpty(),
            )
        }
        else -> null
    }
}

private fun parseArtifacts(raw: Any?, role: ClientArtifactRole): List<ClientArtifactSpec>
{
    val list = raw as? List<*> ?: return emptyList()
    return list.mapNotNull { entry ->
        val m = entry as? Map<*, *> ?: return@mapNotNull null
        val name = (m["name"] as? String)?.trim().orEmpty()
        val urlTemplate = (m["urlTemplate"] as? String)?.trim().orEmpty()
        if (name.isEmpty() || urlTemplate.isEmpty()) return@mapNotNull null
        ClientArtifactSpec(
            name = name,
            role = role,
            urlTemplate = urlTemplate,
            urlTemplateAarch64 = (m["urlTemplateAarch64"] as? String)?.trim()?.ifEmpty { null },
            nameAarch64 = (m["nameAarch64"] as? String)?.trim()?.ifEmpty { null },
            optional = m["optional"] as? Boolean ?: false,
        )
    }
}
