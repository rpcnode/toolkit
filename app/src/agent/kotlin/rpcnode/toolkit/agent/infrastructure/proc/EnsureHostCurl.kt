package rpcnode.toolkit.agent.infrastructure.proc

import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Snapshot downloads use `curl -C -`. Install curl via the host package manager when missing.
 */
object EnsureHostCurl
{
    private val log = LoggerFactory.getLogger(EnsureHostCurl::class.java)

    /** Ensures `curl` is on PATH; installs the OS package when running as root on Linux. */
    fun ensure(): String
    {
        if (onPath("curl"))
        {
            return "curl"
        }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("linux"))
        {
            error(
                "curl is required for snapshot download on this OS ($os). " +
                    "Install curl and restart the agent.",
            )
        }
        if (!runningAsRoot())
        {
            error(
                "curl is not installed. As root: apt-get install -y curl  " +
                    "(or dnf/yum/zypper/pacman/apk — see agent host deps)",
            )
        }
        val mgr = detectPkgMgr()
            ?: error("curl is missing and no package manager was found (apt/dnf/yum/zypper/pacman/apk)")
        log.warn("curl missing — installing via {}", mgr.tool)
        mgr.install("curl")
        if (!onPath("curl"))
        {
            error("failed to install curl via ${mgr.tool}")
        }
        log.info("curl installed via {}", mgr.tool)
        return "curl"
    }

    fun onPath(bin: String): Boolean
    {
        return try
        {
            val p = ProcessBuilder("sh", "-c", "command -v ${shellQuote(bin)}")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0 && out.isNotBlank()
        }
        catch (_: Exception)
        {
            false
        }
    }

    internal fun detectPkgMgr(): PkgMgr?
    {
        return when
        {
            onPath("apt-get") -> PkgMgr.Apt
            onPath("dnf") -> PkgMgr.Dnf
            onPath("yum") -> PkgMgr.Yum
            onPath("zypper") -> PkgMgr.Zypper
            onPath("pacman") -> PkgMgr.Pacman
            onPath("apk") -> PkgMgr.Apk
            else -> null
        }
    }

    private fun shellQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"

    internal enum class PkgMgr(val tool: String)
    {
        Apt("apt-get")
        {
            override fun install(pkg: String)
            {
                run(
                    listOf("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "update", "-qq"),
                    allowFail = true,
                )
                run(listOf("env", "DEBIAN_FRONTEND=noninteractive", "apt-get", "install", "-y", "-qq", pkg))
            }
        },
        Dnf("dnf")
        {
            override fun install(pkg: String) = run(listOf("dnf", "install", "-y", "-q", pkg))
        },
        Yum("yum")
        {
            override fun install(pkg: String) = run(listOf("yum", "install", "-y", "-q", pkg))
        },
        Zypper("zypper")
        {
            override fun install(pkg: String) = run(listOf("zypper", "--non-interactive", "install", "-y", pkg))
        },
        Pacman("pacman")
        {
            override fun install(pkg: String) = run(listOf("pacman", "-Sy", "--noconfirm", pkg))
        },
        Apk("apk")
        {
            override fun install(pkg: String) = run(listOf("apk", "add", "--no-cache", pkg))
        },
        ;

        abstract fun install(pkg: String)

        protected fun run(cmd: List<String>, allowFail: Boolean = false)
        {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            val ok = p.waitFor(10, TimeUnit.MINUTES) && p.exitValue() == 0
            if (!ok && !allowFail)
            {
                error("${cmd.joinToString(" ")} failed: ${out.trim().take(500)}")
            }
        }
    }
}
