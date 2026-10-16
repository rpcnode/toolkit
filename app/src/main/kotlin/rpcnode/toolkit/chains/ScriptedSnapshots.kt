package rpcnode.toolkit.chains

import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfiles
import rpcnode.toolkit.chains.evmfamily.infrastructure.ScriptedSnapshot
import rpcnode.toolkit.chains.polygon.infrastructure.PolygonSnapshot

/** Every network whose snapshot is restored by a host script (`evm-snapshot://` sentinel). The host agent looks them up here. */
object ScriptedSnapshots
{
    val ALL: List<ScriptedSnapshot> = EvmProfiles.ALL + listOf(PolygonSnapshot)
}
