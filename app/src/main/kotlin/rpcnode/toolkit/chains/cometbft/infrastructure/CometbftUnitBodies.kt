package rpcnode.toolkit.chains.cometbft.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/** The systemd unit and command line of one CometBFT node (`<binary> start --home <home> …`). */
object CometbftUnitBodies
{
    fun relativeLog(profile: CometbftProfile) = "logs/${profile.logName}.log"

    fun startArgs(profile: CometbftProfile, env: CometbftEnv, home: String): List<String>
    {
        val ports = profile.ports(env)
        val extra = profile.extraStartArgs.map { it.replace("{grpc_laddr}", (ports.grpcLaddr ?: 0).toString()) }
        return listOf("start", "--home", home) + extra
    }

    fun unit(
        profile: CometbftProfile,
        env: CometbftEnv,
        nodeDir: String,
        execStart: String,
        logFile: String,
        nofile: Int = CometbftSettings.NOFILE,
    ): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load(profile.network.value, "node.service.tmpl"),
            mapOf(
                "description" to "${profile.network.value} (${env.chainId}) — RpcNode",
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )
}
