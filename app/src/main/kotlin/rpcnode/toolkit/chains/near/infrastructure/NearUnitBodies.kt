package rpcnode.toolkit.chains.near.infrastructure

import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmUnit

/** The `neard run` command line + unit (`chains/near/node.service.tmpl`). The RPC is public, Prometheus stays on loopback. */
object NearUnitBodies
{
    fun flags(env: NearEnv, home: String): List<String> = listOf(
        // global flag, must precede the sub-command; `run` rides on it so the flag list below is only `run`'s flags
        "--home=$home run",
        "--rpc-addr=0.0.0.0:${env.rpcPort}",
        "--network-addr=0.0.0.0:${env.p2pPort}",
        "--rpc-prometheus-addr=127.0.0.1:${env.metricsPort}",
    )

    fun unit(env: NearEnv, nodeDir: String, binary: String, home: String, logFile: String): String =
        NonEvmUnit.render(
            network = "near",
            description = "NEAR (${env.chainId}) — RpcNode",
            nodeDir = nodeDir,
            execStart = NonEvmUnit.execStart(binary, flags(env, home)),
            logFile = logFile,
            nofile = NearSettings.NOFILE,
        )

    /** `neard --home H init …` — once per home. Flags VERIFIED in neard/src/cli.rs (InitCmd) at 2.13.4. */
    fun initCommand(binary: String, env: NearEnv, home: String): List<String> = listOf(
        binary, "--home", home, "init",
        "--chain-id", env.chainId,
        "--download-genesis",
        "--download-config", "rpc",
    )
}
