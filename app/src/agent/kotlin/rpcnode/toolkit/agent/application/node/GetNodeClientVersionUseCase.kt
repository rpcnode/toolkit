package rpcnode.toolkit.agent.application.node

import java.nio.file.Path
import rpcnode.toolkit.agent.application.enroll.PanelEnrollmentStore
import rpcnode.toolkit.agent.infrastructure.node.downloadNodeClientVersionFromPanel
import rpcnode.toolkit.agent.infrastructure.node.readNodeClientVersion
import rpcnode.toolkit.agent.infrastructure.node.resolveHostClientVersion

data class NodeClientVersionView(
    val nodeId: String,
    val clientVersion: String,
    val path: String,
)

sealed interface GetNodeClientVersionResult
{
    data class Ok(val view: NodeClientVersionView) : GetNodeClientVersionResult
    data object NotFound : GetNodeClientVersionResult
}

/**
 * On-disk `{nodeDir}/VERSION` for a registered node.
 * Prefers the agent registry [RunningNode.nodeDir] over panel hints; accepts [seedRaw] from the panel pin.
 */
class GetNodeClientVersionUseCase(
    private val registry: RunningNodeRegistry,
    private val enrollment: PanelEnrollmentStore,
    private val panelUrlOverride: String? = System.getenv("PANEL_URL"),
)
{
    suspend operator fun invoke(
        nodeIdRaw: String,
        nodeDirRaw: String? = null,
        seedRaw: String? = null,
    ): GetNodeClientVersionResult
    {
        val nodeId = nodeIdRaw.trim()
        if (nodeId.isEmpty())
        {
            return GetNodeClientVersionResult.NotFound
        }
        val registered = registry.get(nodeId)
        val seed = seedRaw?.trim().orEmpty().ifEmpty { registered?.clientVersion.orEmpty() }
        val dirs = candidateNodeDirs(registered?.nodeDir, nodeDirRaw)
        if (dirs.isEmpty())
        {
            return GetNodeClientVersionResult.NotFound
        }

        for (dir in dirs)
        {
            val version = resolveHostClientVersion(dir, seed = seed)
            if (version.isNotEmpty())
            {
                return ok(nodeId, version, dir)
            }
        }

        val panelUrl = panelUrlOverride?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: enrollment.read()?.panelUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (panelUrl != null && registered != null)
        {
            for (dir in dirs)
            {
                if (downloadNodeClientVersionFromPanel(panelUrl, registered.network, registered.env, dir))
                {
                    val version = readNodeClientVersion(dir)
                    if (version.isNotEmpty())
                    {
                        return ok(nodeId, version, dir)
                    }
                }
            }
        }

        return ok(nodeId, "", dirs.first())
    }

    private fun ok(nodeId: String, version: String, nodeDir: String): GetNodeClientVersionResult.Ok
    {
        return GetNodeClientVersionResult.Ok(
            NodeClientVersionView(
                nodeId = nodeId,
                clientVersion = version,
                path = versionPath(nodeDir),
            ),
        )
    }

    private fun candidateNodeDirs(registryDir: String?, queryDir: String?): List<String>
    {
        return listOfNotNull(
            sanitizeNodeDir(registryDir),
            sanitizeNodeDir(queryDir),
        ).distinct()
    }

    private fun versionPath(nodeDir: String): String =
        Path.of(nodeDir, "VERSION").toAbsolutePath().toString()

    private fun sanitizeNodeDir(raw: String?): String?
    {
        val dir = raw?.trim().orEmpty()
        if (dir.isEmpty() || !dir.startsWith("/") || ".." in dir)
        {
            return null
        }
        return dir
    }
}
