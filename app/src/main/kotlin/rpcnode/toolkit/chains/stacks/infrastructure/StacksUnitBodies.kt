package rpcnode.toolkit.chains.stacks.infrastructure

import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmUnit

object StacksUnitBodies
{
    fun flags(config: String): List<String> = listOf("start --config=$config")

    fun unit(env: StacksEnv, nodeDir: String, binary: String, config: String, logFile: String): String =
        NonEvmUnit.render(
            network = "stacks",
            description = "Stacks (${env.mode}) — RpcNode",
            nodeDir = nodeDir,
            execStart = NonEvmUnit.execStart(binary, flags(config)),
            logFile = logFile,
            nofile = StacksSettings.NOFILE,
        )
}
