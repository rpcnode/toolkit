package rpcnode.toolkit.agent.infrastructure.proc

import org.slf4j.LoggerFactory

/**
 * Picks a host downloader for large snapshot archives.
 * Prefers **aria2c** (multi-connection + resume); falls back to **curl -C -**.
 */
object EnsureHostDownloader
{
    private val log = LoggerFactory.getLogger(EnsureHostDownloader::class.java)

    enum class Tool(val bin: String)
    {
        Aria2("aria2c"),
        Curl("curl"),
    }

    /** Ensures aria2c or curl is on PATH; installs when root on Linux. */
    fun ensure(): Tool
    {
        if (EnsureHostCurl.onPath(Tool.Aria2.bin))
        {
            return Tool.Aria2
        }
        if (runningAsRoot() && System.getProperty("os.name").orEmpty().lowercase().contains("linux"))
        {
            tryInstallAria2()
            if (EnsureHostCurl.onPath(Tool.Aria2.bin))
            {
                return Tool.Aria2
            }
        }
        EnsureHostCurl.ensure()
        return Tool.Curl
    }

    fun available(): Tool? = when
    {
        EnsureHostCurl.onPath(Tool.Aria2.bin) -> Tool.Aria2
        EnsureHostCurl.onPath(Tool.Curl.bin) -> Tool.Curl
        else -> null
    }

    private fun tryInstallAria2()
    {
        val mgr = EnsureHostCurl.detectPkgMgr() ?: return
        log.warn("aria2c missing — installing via {} (multi-connection snapshot downloads)", mgr.tool)
        try
        {
            mgr.install("aria2")
        }
        catch (e: Exception)
        {
            log.warn("aria2 install failed ({}), will use curl: {}", mgr.tool, e.message)
            return
        }
        if (EnsureHostCurl.onPath(Tool.Aria2.bin))
        {
            log.info("aria2c installed via {}", mgr.tool)
        }
    }
}
