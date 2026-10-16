package rpcnode.toolkit.chains.nonevm.infrastructure

import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/** The one systemd unit of a single-process node, rendered from `chains/<network>/node.service.tmpl`. */
object NonEvmUnit
{
    /** Default `LimitNOFILE` of every node unit of the family (polkadot docs 65536, fuel docs >= 32768). */
    const val NOFILE = 1_048_576

    fun render(
        network: String,
        description: String,
        nodeDir: String,
        execStart: String,
        logFile: String,
        nofile: Int = NOFILE,
    ): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load(network, "node.service.tmpl"),
            mapOf(
                "description" to description,
                "node_dir" to nodeDir,
                "exec_start" to execStart,
                "log_file" to logFile,
                "nofile" to nofile.coerceAtLeast(1024).toString(),
            ),
        )

    /** `ExecStart=` command: the binary, then one flag per continuation line (the unit stays readable and flag overrides keep working). */
    fun execStart(binary: String, flags: List<String>): String =
        if (flags.isEmpty()) binary else (listOf(binary) + flags).joinToString(" \\\n  ")
}
