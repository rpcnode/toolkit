package rpcnode.toolkit.agent.application.ports

data class PortCheck(
    val port: Int,
    val free: Boolean,
    val holder: String? = null,
)

/** Whether a TCP port on this host is free to bind, and who holds it when it isn't. */
fun interface PortProbe
{
    fun probe(port: Int): PortCheck
}

/** Checks a batch of ports the panel asked about — e.g. a network/env's fixed client ports. */
class CheckPortsUseCase(
    private val probe: PortProbe,
)
{
    operator fun invoke(ports: List<Int>): List<PortCheck> = ports.distinct().map(probe::probe)
}
