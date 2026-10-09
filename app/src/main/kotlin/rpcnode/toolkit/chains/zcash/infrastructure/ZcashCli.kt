package rpcnode.toolkit.chains.zcash.infrastructure

/**
 * Zcashd resolves relative `-conf=` against datadir (Bitcoin-derived).
 * Network is selected with `-testnet` / `-regtest`, not `-chain=`.
 */
object ZcashCli
{
    fun daemonArgs(nodeDir: String, configFile: String, env: String): List<String>
    {
        return cliArgs(nodeDir, configFile, env) + "-daemon=0"
    }

    fun cliArgs(nodeDir: String, configFile: String, env: String): List<String>
    {
        val root = nodeDir.trim().trimEnd('/')
        val confName = configFile.trim().ifBlank { "zcash.conf" }.trimStart('/')
        val confPath = if (confName.startsWith("/")) confName else "$root/$confName"
        val out = mutableListOf("-datadir=$root", "-conf=$confPath")
        networkFlag(env)?.let { out += it }
        return out
    }

    fun networkFlag(env: String): String?
    {
        return when (env.trim().lowercase())
        {
            "", "mainnet", "main" -> null
            "testnet", "test" -> "-testnet"
            "regtest" -> "-regtest"
            else -> "-testnet"
        }
    }
}
