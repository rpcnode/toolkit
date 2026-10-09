package rpcnode.toolkit.agent.infrastructure.sysctl

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.agent.application.reserve.ReservedPortsHost

class OsReservedPortsHost : ReservedPortsHost
{
    override fun readFile(path: String): String?
    {
        val file = Path.of(path)
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return Files.readString(file)
    }

    override fun writeFile(path: String, data: String): Boolean
    {
        return try
        {
            val file = Path.of(path)
            val parent = file.parent
            if (parent != null)
            {
                Files.createDirectories(parent)
            }
            Files.writeString(file, data)
            true
        }
        catch (_: Exception)
        {
            false
        }
    }

    override fun mkdirAll(path: String)
    {
        try
        {
            Files.createDirectories(Path.of(path))
        }
        catch (_: Exception)
        {
            // IDEA / unprivileged: /etc/rpcnode and sysctl.d stay root-only.
        }
    }

    override fun run(name: String, vararg args: String): Boolean
    {
        return try
        {
            val proc = ProcessBuilder(name, *args)
                .redirectErrorStream(true)
                .start()
            proc.waitFor() == 0
        }
        catch (_: Exception)
        {
            false
        }
    }
}
