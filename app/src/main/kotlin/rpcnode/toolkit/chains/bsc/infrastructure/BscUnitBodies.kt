package rpcnode.toolkit.chains.bsc.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders BSC systemd unit from classpath template
 * `resources/chains/bsc/node.service.tmpl`.
 */
object BscUnitBodies
{
    fun geth(
        env: String,
        bin: String,
        datadir: String,
        configPath: String?,
        rpcPort: Int,
        p2pPort: Int,
        cacheMb: Int,
        logFile: String,
    ): String
    {
        val configFlag = if (!configPath.isNullOrBlank())
        {
            " \\\n  --config $configPath"
        }
        else
        {
            ""
        }
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("bsc", "node.service.tmpl"),
            mapOf(
                "description" to "BNB Smart Chain geth ($env, full) — RpcNode",
                "bin" to bin,
                "datadir" to datadir,
                "config_flag" to configFlag,
                "http_port" to rpcPort.toString(),
                "cache_mb" to cacheMb.toString(),
                "p2p_port" to p2pPort.toString(),
                "log_file" to logFile,
            ),
        )
    }
}
