package rpcnode.toolkit.agent.infrastructure.proxy

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.agent.domain.model.RunningNode
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreRpcAuth

/** Networks whose daemon always demands RPC credentials (bitcoind family). */
val RPC_PROXY_NETWORKS: Set<String> = setOf("bitcoin", "ltc", "doge", "dash", "bch")

/**
 * Where a bitcoind-style node keeps the credentials the RPC proxy has to present:
 *  - Litecoin / Dogecoin / Dash / BCH: `rpcuser` / `rpcpassword`, mirrored by Toolkit in `.toolkit/rpc-auth.env`;
 *  - Bitcoin Core: the `.cookie` file in the chain directory (`__cookie__:<random>`, new on every node start).
 */
object NodeRpcCredentials
{
    /** `user:password` for HTTP Basic auth, or null while the node has not created them yet. */
    fun forNode(node: RunningNode): String? = forNode(node.network, node.env, Path.of(node.nodeDir))

    fun forNode(network: String, env: String, nodeDir: Path): String?
    {
        BitcoreRpcAuth.readFromEnvFile(BitcoreRpcAuth.authEnvPath(nodeDir))?.let { return "${it.user}:${it.password}" }
        if (network.trim().lowercase() != "bitcoin")
        {
            return null
        }
        for (candidate in cookieCandidates(nodeDir, env))
        {
            val line = runCatching {
                if (Files.isRegularFile(candidate)) Files.readString(candidate).trim().lineSequence().firstOrNull().orEmpty() else ""
            }.getOrDefault("")
            if (line.contains(':'))
            {
                return line
            }
        }
        return null
    }

    internal fun cookieCandidates(nodeDir: Path, env: String): List<Path>
    {
        val sub = when (env.trim().lowercase())
        {
            "", "mainnet", "main" -> null
            "testnet", "testnet3", "test" -> "testnet3"
            else -> env.trim().lowercase()
        }
        return listOfNotNull(sub?.let { nodeDir.resolve(it).resolve(".cookie") }, nodeDir.resolve(".cookie"))
    }
}
