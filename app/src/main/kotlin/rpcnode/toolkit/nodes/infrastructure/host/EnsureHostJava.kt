package rpcnode.toolkit.nodes.infrastructure.host

import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Ensures a host JDK/JRE of the required major is installed (java-tron needs 8).
 * Uses the OS package manager when the agent runs as root — same idea as EnsureHostCurl.
 */
object EnsureHostJava
{
    private val log = LoggerFactory.getLogger(EnsureHostJava::class.java)

    /**
     * No-op when [HostJavaBinary] already finds [requiredMajor].
     * Otherwise installs a distro package (root + Linux) and re-resolves.
     */
    fun ensure(requiredMajor: Int)
    {
        when (val have = HostJavaBinary.resolve(requiredMajor))
        {
            is HostJavaBinary.ResolveResult.Found -> return
            is HostJavaBinary.ResolveResult.Missing ->
            {
                // fall through to install
            }
        }
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if (!os.contains("linux"))
        {
            error(
                "Java $requiredMajor required on this OS ($os). " +
                    "Install a JDK $requiredMajor and set JAVA_HOME, then retry Start.",
            )
        }
        if (!runningAsRoot())
        {
            error(
                "Java $requiredMajor required but not installed. " +
                    "As root: install openjdk-$requiredMajor-jre-headless " +
                    "(or set JAVA_HOME to that JDK), then retry.",
            )
        }
        val mgr = detectPkgMgr()
            ?: error(
                "Java $requiredMajor required and no package manager was found " +
                    "(apt/dnf/yum/zypper/pacman/apk)",
            )
        val packages = packagesFor(requiredMajor, mgr)
        log.warn("Java {} missing — installing via {} ({})", requiredMajor, mgr.tool, packages.joinToString(", "))
        for (pkg in packages)
        {
            runCatching { mgr.install(pkg) }
                .onFailure { log.warn("package {} failed: {}", pkg, it.message) }
            when (HostJavaBinary.resolve(requiredMajor))
            {
                is HostJavaBinary.ResolveResult.Found ->
                {
                    log.info("Java {} ready after installing {}", requiredMajor, pkg)
                    return
                }
                is HostJavaBinary.ResolveResult.Missing -> Unit
            }
        }
        when (val after = HostJavaBinary.resolve(requiredMajor))
        {
            is HostJavaBinary.ResolveResult.Found -> return
            is HostJavaBinary.ResolveResult.Missing -> error(after.detail)
        }
    }

    private fun packagesFor(major: Int, mgr: PkgMgr): List<String>
    {
        return when (mgr)
        {
            PkgMgr.Apt -> listOf("openjdk-$major-jre-headless", "openjdk-$major-jdk-headless")
            PkgMgr.Dnf, PkgMgr.Yum ->
                if (major == 8)
                {
                    listOf("java-1.8.0-openjdk-headless", "java-1.8.0-openjdk")
                }
                else
                {
                    listOf("java-$major-openjdk-headless", "java-$major-openjdk")
                }
            PkgMgr.Zypper ->
                if (major == 8)
                {
                    listOf("java-1_8_0-openjdk", "java-1_8_0-openjdk-headless")
                }
                else
                {
                    listOf("java-$major-openjdk", "java-$major-openjdk-headless")
                }
            PkgMgr.Pacman -> listOf("jre$major-openjdk-headless", "jdk$major-openjdk")
            PkgMgr.Apk ->
                if (major == 8)
                {
                    listOf("openjdk8-jre", "openjdk8")
                }
                else
                {
                    listOf("openjdk$major-jre", "openjdk$major")
                }
        }
    }

    private fun runningAsRoot(): Boolean
    {
        return try
        {
            val p = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            p.waitFor() == 0 && out == "0"
        }
        catch (_: Exception)
        {
            false
        }
    }

    private fun onPath(bin: String): Boolean
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

    private fun detectPkgMgr(): PkgMgr?
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

    private enum class PkgMgr(val tool: String)
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
            override fun install(pkg: String) =
                run(listOf("zypper", "--non-interactive", "install", "-y", pkg))
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
            val ok = p.waitFor(15, TimeUnit.MINUTES) && p.exitValue() == 0
            if (!ok && !allowFail)
            {
                error("${cmd.joinToString(" ")} failed: ${out.trim().take(500)}")
            }
        }
    }
}
