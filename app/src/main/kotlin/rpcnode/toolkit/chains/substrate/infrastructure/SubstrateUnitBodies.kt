package rpcnode.toolkit.chains.substrate.infrastructure

import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmUnit

/** The systemd unit of one Substrate node (`chains/<network>/node.service.tmpl`). */
object SubstrateUnitBodies
{
    fun execStart(binary: String, flags: List<String>): String = NonEvmUnit.execStart(binary, flags)

    fun unit(spec: SubstrateSpec, env: SubstrateEnv, nodeDir: String, execStart: String, logFile: String): String =
        NonEvmUnit.render(
            network = spec.network.value,
            description = "${spec.network.value} (${env.id}) — RpcNode",
            nodeDir = nodeDir,
            execStart = execStart,
            logFile = logFile,
        )
}
