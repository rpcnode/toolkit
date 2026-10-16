package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.nio.file.Path
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/** One systemd unit of an EVM-family node: what runs, where it logs and how it is stopped. */
data class EvmUnit(
    val description: String,
    /** Executable followed by sub-command words (`tempo node`). */
    val command: List<String>,
    /** One flag (with its value) per entry; rendered one per continuation line so operator overrides can rewrite it. */
    val flags: List<String>,
    val workDir: Path,
    val logFile: Path,
    /** `KEY=value` lines of `Environment=`. */
    val environment: List<String> = emptyList(),
    /** Units this one starts after (and, for [requires], needs). */
    val after: List<String> = emptyList(),
    val requires: List<String> = emptyList(),
    val killSignal: String = "SIGTERM",
    val timeoutStopSec: Int = 300,
    val nofile: Int = EvmCapacity.NOFILE,
    /** `Restart=` value; only templates with `{{restart}}` use it (Monad's consensus and execution units never restart by themselves). */
    val restart: String = "on-failure",
    /** Extra `[Service]` lines (`Key=value`), for templates with `{{service_extra}}`. */
    val serviceExtra: List<String> = emptyList(),
)

/** Renders [EvmUnit]s through the network's own `node.service.tmpl` / `companion.service.tmpl`. */
object EvmUnitBodies
{
    fun unitName(network: String, env: String): String = "rpcnode-$network-$env.service"

    fun companionName(network: String, role: String, env: String): String = "rpcnode-$network-$role-$env.service"

    /** `ExecStart` value: the command, then every flag on its own continuation line. */
    fun execStart(unit: EvmUnit): String =
        (listOf(unit.command.joinToString(" ")) + unit.flags).joinToString(" \\\n  ")

    fun render(network: String, unit: EvmUnit, template: String = "node.service.tmpl"): String
    {
        val after = unit.after.joinToString("") { " $it" }
        val requires = if (unit.requires.isEmpty()) "" else "Requires=${unit.requires.joinToString(" ")}\n"
        val env = unit.environment.joinToString("") { "Environment=$it\n" }
        return HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load(network, template),
            mapOf(
                "description" to unit.description,
                "after" to after,
                "unit_extra" to requires,
                "work_dir" to unit.workDir.toString(),
                "environment" to env,
                "exec_start" to execStart(unit),
                "timeout_stop" to unit.timeoutStopSec.toString(),
                "kill_signal" to unit.killSignal,
                "log_file" to unit.logFile.toString(),
                "nofile" to unit.nofile.coerceAtLeast(1024).toString(),
                "restart" to unit.restart,
                "service_extra" to unit.serviceExtra.joinToString("") { "$it\n" },
            ),
        )
    }

    const val COMPANION_TEMPLATE = "companion.service.tmpl"
}
