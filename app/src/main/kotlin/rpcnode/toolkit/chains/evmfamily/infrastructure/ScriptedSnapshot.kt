package rpcnode.toolkit.chains.evmfamily.infrastructure

/**
 * A network whose snapshot is restored by a script on the host (the `evm-snapshot://<network>/<env>/<kind>` sentinel): the
 * binaries the script uses and the script of one restore. EVM-family profiles are one kind; Polygon (Bor + Heimdall) is another.
 */
interface ScriptedSnapshot
{
    /** `NetworkId` value of the sentinel's first segment. */
    val snapshotNetwork: String

    /** Binaries the restore needs under `<dest>/bin` (empty when it only uses host tools). */
    fun snapshotBinaries(envId: String): List<EvmBinary>

    /** The bash script of one restore; null when this network has none. Throws with an operator-facing text when it must not run. */
    fun snapshotScript(ref: EvmSnapshotRef, paths: EvmSnapshotPaths): String?
}
