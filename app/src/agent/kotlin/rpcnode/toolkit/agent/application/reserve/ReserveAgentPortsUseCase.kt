package rpcnode.toolkit.agent.application.reserve

import java.nio.file.Path
import rpcnode.toolkit.agent.domain.model.EphemeralPortRange
import rpcnode.toolkit.agent.domain.model.formatPortList
import rpcnode.toolkit.agent.domain.model.planReservedPorts

data class ReservedPortsStatus(
    val checked: Boolean,
    val ok: Boolean,
    val applied: Boolean = false,
    val range: EphemeralPortRange? = null,
    val want: List<Int> = emptyList(),
    val missing: List<Int> = emptyList(),
    val detail: String,
)

/**
 * Take the static agent API port out of the kernel ephemeral allocator so a random process
 * cannot bind it before we listen.
 *
 * Runs at process start (systemd unit is root). IDEA / unprivileged runs log a warning and continue.
 */
class ReserveAgentPortsUseCase(
    private val host: ReservedPortsHost,
    private val ours: List<Int>,
    private val confPath: String = DEFAULT_CONF_PATH,
    private val rangeFile: String = DEFAULT_RANGE_FILE,
)
{
    operator fun invoke(): ReservedPortsStatus
    {
        writeRangeFile()
        val rangeRaw = host.readFile(LOCAL_PORT_RANGE_PROC)
            ?: return ReservedPortsStatus(
                checked = false,
                ok = false,
                detail = "cannot read $LOCAL_PORT_RANGE_PROC — port reservation not applicable",
            )
        val reservedRaw = host.readFile(RESERVED_PORTS_PROC).orEmpty()
        val plan = planReservedPorts(rangeRaw, reservedRaw, ours)
            ?: return ReservedPortsStatus(
                checked = false,
                ok = false,
                detail = "ip_local_port_range is not a range — port reservation not applicable",
            )
        val body = reservedPortsConf(plan.want)
        host.mkdirAll(Path.of(confPath).parent?.toString() ?: "/etc/sysctl.d")
        val confStale = host.readFile(confPath) != body
        if (confStale)
        {
            host.writeFile(confPath, body)
        }
        val inspected = ReservedPortsStatus(
            checked = true,
            ok = plan.missing.isEmpty(),
            range = plan.range,
            want = plan.want,
            missing = plan.missing,
            detail = reservedPortsDetail(plan.missing.isEmpty(), plan.need.size, plan.missing.size, plan.range),
        )
        if (inspected.ok)
        {
            return inspected.copy(applied = confStale)
        }
        val list = formatPortList(plan.want)
        if (!applyLive(list))
        {
            val after = inspect()
            if (after.ok)
            {
                return after.copy(applied = true)
            }
            return after.copy(
                detail = "sysctl $SYSCTL_KEY was refused — ${after.missing.size} port(s) stay inside " +
                    "the ephemeral range ${after.range} and can be taken by any process",
            )
        }
        val after = inspect()
        if (!after.ok)
        {
            return after.copy(
                applied = true,
                detail = "sysctl $SYSCTL_KEY did not stick — ${after.missing.size} port(s) stay inside " +
                    "the ephemeral range ${after.range} and can be taken by any process",
            )
        }
        return after.copy(applied = true)
    }

    private fun inspect(): ReservedPortsStatus
    {
        val rangeRaw = host.readFile(LOCAL_PORT_RANGE_PROC)
            ?: return ReservedPortsStatus(
                checked = false,
                ok = false,
                detail = "cannot read $LOCAL_PORT_RANGE_PROC — port reservation not applicable",
            )
        val reservedRaw = host.readFile(RESERVED_PORTS_PROC).orEmpty()
        val plan = planReservedPorts(rangeRaw, reservedRaw, ours)
            ?: return ReservedPortsStatus(
                checked = false,
                ok = false,
                detail = "ip_local_port_range is not a range — port reservation not applicable",
            )
        return ReservedPortsStatus(
            checked = true,
            ok = plan.missing.isEmpty(),
            range = plan.range,
            want = plan.want,
            missing = plan.missing,
            detail = reservedPortsDetail(plan.missing.isEmpty(), plan.need.size, plan.missing.size, plan.range),
        )
    }

    private fun applyLive(list: String): Boolean
    {
        if (list.isEmpty())
        {
            return true
        }
        if (host.writeFile(RESERVED_PORTS_PROC, list))
        {
            return true
        }
        return host.run("sysctl", "-w", "$SYSCTL_KEY=$list")
    }

    private fun writeRangeFile()
    {
        val sorted = ours.filter { it in 1..65_535 }.sorted()
        if (sorted.isEmpty())
        {
            return
        }
        val parent = Path.of(rangeFile).parent?.toString() ?: return
        host.mkdirAll(parent)
        host.writeFile(rangeFile, formatPortList(sorted) + "\n")
    }

    companion object
    {
        const val DEFAULT_CONF_PATH = "/etc/sysctl.d/99-rpcnode-agent-ports.conf"
        const val DEFAULT_RANGE_FILE = "/etc/rpcnode/rpcnode-agent.ports"
        const val SYSCTL_KEY = "net.ipv4.ip_local_reserved_ports"
        const val LOCAL_PORT_RANGE_PROC = "/proc/sys/net/ipv4/ip_local_port_range"
        const val RESERVED_PORTS_PROC = "/proc/sys/net/ipv4/ip_local_reserved_ports"
    }
}

fun reservedPortsConf(ports: List<Int>): String =
    "# Managed by rpcnode-agent — the agent API port and the client catalog's fixed ports must " +
        "not be handed out as ephemeral source ports.\n" +
        "${ReserveAgentPortsUseCase.SYSCTL_KEY} = ${formatPortList(ports)}\n"

fun reservedPortsDetail(ok: Boolean, needSize: Int, missingSize: Int, range: EphemeralPortRange): String
{
    if (needSize == 0)
    {
        return "no reserved port falls inside ip_local_port_range $range — nothing to reserve"
    }
    if (ok)
    {
        return "$needSize port(s) reserved out of ip_local_port_range $range"
    }
    return "$missingSize of $needSize port(s) not reserved inside ip_local_port_range $range"
}
