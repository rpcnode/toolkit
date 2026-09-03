package rpcnode.toolkit.agent.application.reserve

/** Filesystem + `sysctl` the reservation needs. Tests use a fake. */
interface ReservedPortsHost
{
    fun readFile(path: String): String?

    fun writeFile(path: String, data: String): Boolean

    fun mkdirAll(path: String)

    fun run(name: String, vararg args: String): Boolean
}
