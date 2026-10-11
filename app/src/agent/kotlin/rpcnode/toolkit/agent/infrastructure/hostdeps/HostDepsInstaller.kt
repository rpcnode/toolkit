package rpcnode.toolkit.agent.infrastructure.hostdeps

import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.application.hostdeps.HostDepProbeItem
import rpcnode.toolkit.agent.application.hostdeps.HostDepRequest
import rpcnode.toolkit.agent.infrastructure.proc.EnsureHostCurl
import rpcnode.toolkit.nodes.infrastructure.host.EnsureHostJava
import rpcnode.toolkit.nodes.infrastructure.host.HostJavaBinary
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Probe / install OS packages and JDK majors on the host.
 * Package manager detection mirrors [EnsureHostCurl].
 */
class HostDepsInstaller
{
    private val log = LoggerFactory.getLogger(HostDepsInstaller::class.java)

    fun probe(deps: List<HostDepRequest>): List<HostDepProbeItem> =
        deps.map { dep ->
            when (val kind = dep.kind.trim().lowercase())
            {
                "java" -> probeJava(dep)
                "clang" -> probeClang(dep)
                "package", "binary" -> probePackageOrBinary(dep)
                else -> HostDepProbeItem(id = dep.id, present = false, detail = "unknown kind: $kind")
            }
        }

    fun installOne(dep: HostDepRequest): HostDepProbeItem
    {
        return when (dep.kind.trim().lowercase())
        {
            "java" ->
            {
                val major = dep.javaMajor.takeIf { it > 0 } ?: 8
                try
                {
                    EnsureHostJava.ensure(major)
                    probeJava(dep)
                }
                catch (e: Exception)
                {
                    HostDepProbeItem(
                        id = dep.id,
                        present = false,
                        detail = e.message?.ifBlank { null } ?: "java install failed",
                    )
                }
            }
            "clang" -> installClang(dep)
            "package", "binary" ->
            {
                val pkg = dep.name.trim().ifEmpty { dep.id.trim() }
                if (pkg.isEmpty())
                {
                    return HostDepProbeItem(id = dep.id, present = false, detail = "empty package name")
                }
                if (isPresent(pkg))
                {
                    return HostDepProbeItem(id = dep.id, present = true, detail = "already present")
                }
                try
                {
                    installPackage(pkg)
                    val ok = isPresent(pkg)
                    HostDepProbeItem(
                        id = dep.id,
                        present = ok,
                        detail = if (ok) "installed" else "still missing after install",
                    )
                }
                catch (e: Exception)
                {
                    HostDepProbeItem(
                        id = dep.id,
                        present = false,
                        detail = e.message?.ifBlank { null } ?: "install failed",
                    )
                }
            }
            else -> HostDepProbeItem(id = dep.id, present = false, detail = "unknown kind")
        }
    }

    /** `clang-21` → 21. */
    private fun clangMajor(dep: HostDepRequest): Int? =
        Regex("""clang-(\d{1,3})""").matchEntire(dep.name.trim().ifEmpty { dep.id.trim() })
            ?.groupValues?.get(1)?.toIntOrNull()

    private fun probeClang(dep: HostDepRequest): HostDepProbeItem
    {
        val major = clangMajor(dep)
            ?: return HostDepProbeItem(id = dep.id, present = false, detail = "bad clang dependency '${dep.name}'")
        val have = ClangVersion.major(runVersion("clang"))
        return if (have == major)
        {
            HostDepProbeItem(id = dep.id, present = true, detail = "clang $major")
        }
        else
        {
            HostDepProbeItem(
                id = dep.id,
                present = false,
                detail = if (have == null) "clang missing" else "clang $have, need $major",
            )
        }
    }

    /** Installs clang-N from apt.llvm.org (of the Ubuntu/Debian release the system is based on) as the default clang. */
    private fun installClang(dep: HostDepRequest): HostDepProbeItem
    {
        val major = clangMajor(dep)
            ?: return HostDepProbeItem(id = dep.id, present = false, detail = "bad clang dependency '${dep.name}'")
        if (probeClang(dep).present)
        {
            return HostDepProbeItem(id = dep.id, present = true, detail = "already present")
        }
        return try
        {
            val functions = HostSystemdUnitTemplate.load("default", "clang-functions.sh")
            val script = "set -euo pipefail\n$functions\nensure_clang_major $major\n"
            // The script goes in on stdin, not as `bash -c`: nothing but its own output ends up in the logs.
            val proc = ProcessBuilder("bash", "-s").redirectErrorStream(true).start()
            proc.outputStream.use { it.write(script.toByteArray()) }
            val out = proc.inputStream.bufferedReader().readText()
            val finished = proc.waitFor(30, TimeUnit.MINUTES)
            if (!finished)
            {
                proc.destroyForcibly()
                return HostDepProbeItem(id = dep.id, present = false, detail = "clang install timed out")
            }
            val tail = out.lines().filter { it.isNotBlank() }.takeLast(3).joinToString(" | ")
            if (proc.exitValue() != 0)
            {
                log.warn("clang-{} install failed: {}", major, tail)
                return HostDepProbeItem(
                    id = dep.id,
                    present = false,
                    detail = out.lines().lastOrNull { it.startsWith("ERROR") } ?: tail.ifBlank { "clang install failed" },
                )
            }
            probeClang(dep).let { if (it.present) it.copy(detail = "installed") else it }
        }
        catch (e: Exception)
        {
            HostDepProbeItem(id = dep.id, present = false, detail = e.message?.ifBlank { null } ?: "clang install failed")
        }
    }

    private fun runVersion(bin: String): String =
        try
        {
            val p = ProcessBuilder(bin, "--version").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor(10, TimeUnit.SECONDS)) out else ""
        }
        catch (_: Exception)
        {
            ""
        }

    private fun probeJava(dep: HostDepRequest): HostDepProbeItem
    {
        val major = dep.javaMajor.takeIf { it > 0 } ?: 8
        return when (val got = HostJavaBinary.resolve(major))
        {
            is HostJavaBinary.ResolveResult.Found ->
                HostDepProbeItem(id = dep.id, present = true, detail = got.path)
            is HostJavaBinary.ResolveResult.Missing ->
                HostDepProbeItem(id = dep.id, present = false, detail = got.detail)
        }
    }

    private fun probePackageOrBinary(dep: HostDepRequest): HostDepProbeItem
    {
        val pkg = dep.name.trim().ifEmpty { dep.id.trim() }
        if (pkg.isEmpty())
        {
            return HostDepProbeItem(id = dep.id, present = false, detail = "empty name")
        }
        return if (isPresent(pkg))
        {
            HostDepProbeItem(id = dep.id, present = true, detail = "present")
        }
        else
        {
            HostDepProbeItem(id = dep.id, present = false, detail = "missing")
        }
    }

    private fun isPresent(pkg: String): Boolean
    {
        val bin = wellKnownBinary(pkg)
        if (bin != null && EnsureHostCurl.onPath(bin))
        {
            return true
        }
        if (EnsureHostCurl.onPath(pkg))
        {
            return true
        }
        return packageInstalled(pkg)
    }

    private fun wellKnownBinary(pkg: String): String? =
        when (pkg.trim().lowercase())
        {
            "aria2" -> "aria2c"
            "ca-certificates" -> null
            "build-essential" -> "gcc"
            "pkg-config" -> "pkg-config"
            "libudev-dev", "libssl-dev", "libclang-dev", "libprotobuf-dev" -> null
            "protobuf-compiler" -> "protoc"
            "python3-pip" -> "pip3"
            else -> pkg.trim().lowercase().takeIf { it.matches(Regex("^[a-z0-9.+_-]+$")) && !it.contains("lib") }
        }

    private fun packageInstalled(pkg: String): Boolean
    {
        val checks = listOf(
            listOf("dpkg-query", "-W", "-f=\${Status}", pkg),
            listOf("rpm", "-q", pkg),
            listOf("pacman", "-Q", pkg),
            listOf("apk", "info", "-e", pkg),
        )
        for (cmd in checks)
        {
            try
            {
                if (!EnsureHostCurl.onPath(cmd[0]))
                {
                    continue
                }
                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText().trim()
                if (!p.waitFor(8, TimeUnit.SECONDS) || p.exitValue() != 0)
                {
                    continue
                }
                if (cmd[0] == "dpkg-query")
                {
                    if (out.contains("install ok installed"))
                    {
                        return true
                    }
                }
                else if (out.isNotBlank())
                {
                    return true
                }
            }
            catch (_: Exception)
            {
                // try next
            }
        }
        return false
    }

    private fun installPackage(pkg: String)
    {
        val mgr = EnsureHostCurl.detectPkgMgr()
            ?: error("no package manager (apt/dnf/yum/zypper/pacman/apk)")
        log.warn("host dep {} missing — installing via {}", pkg, mgr.tool)
        mgr.install(pkg)
    }
}

object ClangVersion
{
    private val re = Regex("""version\s+(\d+)""")

    /** Major from the first line of `clang --version` ("Ubuntu clang version 21.1.8 (...)"), null when absent. */
    fun major(versionOutput: String): Int? =
        re.find(versionOutput.lineSequence().firstOrNull().orEmpty())?.groupValues?.get(1)?.toIntOrNull()
}
