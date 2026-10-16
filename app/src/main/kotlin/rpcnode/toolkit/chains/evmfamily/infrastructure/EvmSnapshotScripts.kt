package rpcnode.toolkit.chains.evmfamily.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Scripted snapshot = shared prelude (`chains/default/evm-snapshot-prelude.sh.tmpl`: state file, marker, unit stop,
 * resumable `download`) + the network's own `chains/<id>/snapshot.sh.tmpl` body. Both use `{{name}}` placeholders.
 */
object EvmSnapshotScripts
{
    /** Quotes [raw] for a single-quoted bash string. */
    fun q(raw: String): String = raw.replace("'", "'\\''")

    /**
     * [units] are the systemd units stopped before the restore (primary + companions of the env).
     * [vars] are the body's own placeholders; values are quoted by the caller where needed.
     */
    fun render(network: String, paths: EvmSnapshotPaths, units: List<String>, vars: Map<String, String>): String
    {
        val prelude = HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("default", "evm-snapshot-prelude.sh.tmpl"),
            mapOf(
                "root" to q(paths.root.toString()),
                "marker" to q(paths.marker.toString()),
                "state" to q(paths.state.toString()),
                "log" to q(paths.logFile.toString()),
                "units" to units.joinToString(" "),
            ),
        )
        val body = HostSystemdUnitTemplate.render(HostSystemdUnitTemplate.load(network, "snapshot.sh.tmpl"), vars)
        return prelude + "\n" + body
    }
}
