package rpcnode.toolkit.agent.application.node

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreRpcAuth

data class NodeRpcAuthView(
    val nodeId: String,
    val user: String,
    val password: String,
    val path: String,
)

sealed interface GetNodeRpcAuthResult
{
    data class Ok(val view: NodeRpcAuthView) : GetNodeRpcAuthResult
    /** Network does not use rpcuser/rpcpassword (e.g. Bitcoin cookie). */
    data object NotApplicable : GetNodeRpcAuthResult
    data object NotFound : GetNodeRpcAuthResult
}

/**
 * Read Dash / LTC / Doge / BCH JSON-RPC credentials from `.toolkit/rpc-auth.env`
 * (written by client sync), with a conf fallback when the env file is missing.
 */
class GetNodeRpcAuthUseCase(
    private val registry: RunningNodeRegistry,
)
{
    operator fun invoke(
        nodeIdRaw: String,
        networkRaw: String? = null,
        nodeDirRaw: String? = null,
    ): GetNodeRpcAuthResult
    {
        val nodeId = nodeIdRaw.trim()
        if (nodeId.isEmpty())
        {
            return GetNodeRpcAuthResult.NotFound
        }
        val registered = registry.get(nodeId)
        val network = (networkRaw?.trim().orEmpty().ifEmpty { registered?.network.orEmpty() })
            .lowercase()
        if (network.isEmpty())
        {
            return GetNodeRpcAuthResult.NotFound
        }
        if (!BitcoreRpcAuth.needsRpcUserPassword(network))
        {
            return GetNodeRpcAuthResult.NotApplicable
        }
        val dirs = candidateNodeDirs(registered?.nodeDir, nodeDirRaw)
        if (dirs.isEmpty())
        {
            return GetNodeRpcAuthResult.NotFound
        }
        for (dir in dirs)
        {
            val nodeDir = Path.of(dir)
            val envPath = BitcoreRpcAuth.authEnvPath(nodeDir)
            val fromEnv = BitcoreRpcAuth.readFromEnvFile(envPath)
            if (fromEnv != null)
            {
                return GetNodeRpcAuthResult.Ok(
                    NodeRpcAuthView(
                        nodeId = nodeId,
                        user = fromEnv.user,
                        password = fromEnv.password,
                        path = envPath.toAbsolutePath().toString(),
                    ),
                )
            }
            val fromConf = readConfCreds(nodeDir)
            if (fromConf != null)
            {
                return GetNodeRpcAuthResult.Ok(
                    NodeRpcAuthView(
                        nodeId = nodeId,
                        user = fromConf.user,
                        password = fromConf.password,
                        path = fromConf.path,
                    ),
                )
            }
        }
        return GetNodeRpcAuthResult.NotFound
    }

    private fun candidateNodeDirs(registryDir: String?, queryDir: String?): List<String> =
        listOfNotNull(
            sanitizeNodeDir(registryDir),
            sanitizeNodeDir(queryDir),
        ).distinct()

    private fun sanitizeNodeDir(raw: String?): String?
    {
        val dir = raw?.trim().orEmpty()
        if (dir.isEmpty() || !dir.startsWith("/") || ".." in dir)
        {
            return null
        }
        return dir
    }

    private data class ConfCreds(
        val user: String,
        val password: String,
        val path: String,
    )

    private fun readConfCreds(nodeDir: Path): ConfCreds?
    {
        if (!Files.isDirectory(nodeDir))
        {
            return null
        }
        val names = listOf(
            "dash.conf",
            "litecoin.conf",
            "dogecoin.conf",
            "bitcoin.conf",
            "bch.conf",
        )
        for (name in names)
        {
            val path = nodeDir.resolve(name)
            if (!Files.isRegularFile(path))
            {
                continue
            }
            val creds = try
            {
                BitcoreRpcAuth.readFromConf(Files.readString(path))
            }
            catch (_: Exception)
            {
                null
            } ?: continue
            return ConfCreds(
                user = creds.user,
                password = creds.password,
                path = path.toAbsolutePath().toString(),
            )
        }
        return null
    }
}
