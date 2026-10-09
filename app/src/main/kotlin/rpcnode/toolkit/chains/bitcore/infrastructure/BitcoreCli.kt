package rpcnode.toolkit.chains.bitcore.infrastructure

/**
 * Bitcoin Core and forks resolve relative `-conf=` against [datadir], not cwd.
 * Always pass absolute `-datadir` / `-conf` on the command line before the config is read.
 */
object BitcoreCli
{
    fun daemonArgs(nodeDir: String, configFile: String, env: String, chainArg: (String) -> String?): List<String>
    {
        val base = sharedArgs(nodeDir, configFile, env, chainArg)
        return base + "-daemon=0"
    }

    fun cliArgs(nodeDir: String, configFile: String, env: String, chainArg: (String) -> String?): List<String> =
        sharedArgs(nodeDir, configFile, env, chainArg)

    /** Bitcoin Core env ids (mainnet / testnet4 / signet / regtest). */
    fun bitcoinChainArg(env: String): String?
    {
        return when (env.trim().lowercase())
        {
            "", "mainnet", "main" -> null
            "testnet", "testnet3", "test" -> "-chain=test"
            "testnet4" -> "-chain=testnet4"
            "signet" -> "-chain=signet"
            "regtest" -> "-chain=regtest"
            else -> "-chain=${env.trim().lowercase()}"
        }
    }

    /** Litecoin / Dogecoin / Dash / BCH — mainnet + test + regtest. */
    fun classicChainArg(env: String): String?
    {
        return when (env.trim().lowercase())
        {
            "", "mainnet", "main" -> null
            "testnet", "testnet3", "testnet4", "test" -> "-chain=test"
            "signet" -> "-chain=signet"
            "regtest" -> "-chain=regtest"
            else -> "-chain=${env.trim().lowercase()}"
        }
    }

    private fun sharedArgs(
        nodeDir: String,
        configFile: String,
        env: String,
        chainArg: (String) -> String?,
    ): List<String>
    {
        val root = nodeDir.trim().trimEnd('/')
        val confName = configFile.trim().ifBlank { "bitcoin.conf" }.trimStart('/')
        val confPath = if (confName.startsWith("/")) confName else "$root/$confName"
        val out = mutableListOf("-datadir=$root", "-conf=$confPath")
        chainArg(env)?.let { out += it }
        return out
    }
}
