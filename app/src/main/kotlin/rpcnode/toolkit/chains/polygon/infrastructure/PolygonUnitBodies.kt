package rpcnode.toolkit.chains.polygon.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Renders polygon systemd units from classpath templates under
 * `resources/chains/polygon/` (`node.service.tmpl`, `heimdall.service.tmpl`).
 */
object PolygonUnitBodies
{
    fun bor(
        env: String,
        bin: String,
        configPath: String,
        heimdallUnit: String,
        logFile: String,
    ): String
    {
        val after = heimdallUnit.trim().let { if (it.endsWith(".service")) it else "$it.service" }
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polygon", "node.service.tmpl"),
            mapOf(
                "description" to "Polygon Bor ($env) — RpcNode",
                "heimdall_unit" to after,
                "bin" to bin,
                "config_path" to configPath,
                "log_file" to logFile,
            ),
        )
    }

    fun heimdall(
        env: String,
        bin: String,
        home: String,
        logFile: String,
    ): String
    {
        val chain = env.trim().lowercase().ifEmpty { "mainnet" }
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polygon", "heimdall.service.tmpl"),
            mapOf(
                "description" to "Polygon Heimdall ($chain) — RpcNode",
                "bin" to bin,
                "home" to home,
                "chain" to chain,
                "log_file" to logFile,
            ),
        )
    }

    fun heimdallUnitName(env: String): String =
        "rpcnode-polygon-heimdall-${env.trim().lowercase()}.service"
}
