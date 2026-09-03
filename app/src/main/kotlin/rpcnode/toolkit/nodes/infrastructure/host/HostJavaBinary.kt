package rpcnode.toolkit.nodes.infrastructure.host

import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a host `java` binary, optionally pinned to a major version
 * (java-tron requires 8 on amd64).
 */
object HostJavaBinary
{
    fun resolve(requiredMajor: Int? = null): ResolveResult
    {
        val candidates = candidateHomes(requiredMajor)
        for (home in candidates)
        {
            val bin = home.resolve("bin").resolve("java")
            if (!isJavaExecutable(bin))
            {
                continue
            }
            val major = majorVersion(bin) ?: continue
            if (requiredMajor == null || major == requiredMajor)
            {
                return ResolveResult.Found(bin.toAbsolutePath().toString(), major)
            }
        }
        val pathJava = which("java")
        if (pathJava != null)
        {
            val major = majorVersion(Path.of(pathJava))
            if (requiredMajor == null)
            {
                return ResolveResult.Found(pathJava, major)
            }
            if (major == requiredMajor)
            {
                return ResolveResult.Found(pathJava, major)
            }
            return ResolveResult.Missing(
                "Java $requiredMajor required; found Java ${major ?: "?"} at $pathJava " +
                    "(install openjdk-$requiredMajor-jre-headless or set JAVA_HOME to that JDK)",
            )
        }
        return if (requiredMajor != null)
        {
            ResolveResult.Missing(
                "Java $requiredMajor not found (install openjdk-$requiredMajor-jre-headless " +
                    "or set JAVA_HOME to a Java $requiredMajor install)",
            )
        }
        else
        {
            ResolveResult.Missing("java binary not found (set JAVA_HOME or install java on PATH)")
        }
    }

    /** Parse major from `java -version` stderr (`1.8.0_xxx` → 8, `25.0.4` → 25). */
    fun majorVersion(javaBin: Path): Int?
    {
        if (!isJavaExecutable(javaBin))
        {
            return null
        }
        return try
        {
            val p = ProcessBuilder(javaBin.toString(), "-version")
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            parseMajorFromVersionOutput(out)
        }
        catch (_: Exception)
        {
            null
        }
    }

    fun parseMajorFromVersionOutput(text: String): Int?
    {
        val m = Regex("""version\s+"([^"]+)"""").find(text) ?: return null
        val ver = m.groupValues[1]
        val parts = ver.split('.', '_')
        if (parts.isEmpty())
        {
            return null
        }
        val first = parts[0].toIntOrNull() ?: return null
        if (first == 1)
        {
            return parts.getOrNull(1)?.toIntOrNull()
        }
        return first
    }

    private fun candidateHomes(requiredMajor: Int?): List<Path>
    {
        val out = mutableListOf<Path>()
        fun add(path: Path)
        {
            if (out.none { it == path })
            {
                out.add(path)
            }
        }
        System.getenv("JAVA_HOME")?.trim()?.takeIf { it.isNotEmpty() }?.let { add(Path.of(it)) }
        val major = requiredMajor ?: return out
        add(Path.of("/usr/lib/jvm/java-$major-openjdk-amd64"))
        add(Path.of("/usr/lib/jvm/java-$major-openjdk-arm64"))
        add(Path.of("/usr/lib/jvm/java-$major-openjdk"))
        add(Path.of("/usr/lib/jvm/java-1.$major.0-openjdk-amd64"))
        add(Path.of("/usr/lib/jvm/jre-$major-openjdk"))
        add(Path.of("/opt/rpcnode/jdk$major"))
        add(Path.of("/opt/rpcnode/jdk-$major"))
        if (major == 8)
        {
            add(Path.of("/usr/lib/jvm/java-8-oracle"))
        }
        for (root in listOf(Path.of("/usr/lib/jvm"), Path.of("/opt/rpcnode")))
        {
            if (!Files.isDirectory(root))
            {
                continue
            }
            runCatching {
                Files.list(root).use { stream ->
                    stream.filter { Files.isDirectory(it) }.forEach { dir ->
                        val name = dir.fileName.toString().lowercase()
                        if (
                            name.contains("jdk-$major") ||
                            name.contains("java-$major") ||
                            name.contains("temurin-$major") ||
                            name.contains("openjdk-$major") ||
                            (major == 8 && (name.contains("1.8.0") || name.contains("java-8")))
                        )
                        {
                            add(dir)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun isJavaExecutable(bin: Path): Boolean =
        Files.isRegularFile(bin) && Files.isExecutable(bin)

    private fun which(cmd: String): String?
    {
        return try
        {
            val p = ProcessBuilder("which", cmd).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText().trim()
            if (p.waitFor() == 0 && out.isNotEmpty() && Files.isExecutable(Path.of(out))) out else null
        }
        catch (_: Exception)
        {
            null
        }
    }

    sealed interface ResolveResult
    {
        data class Found(val path: String, val major: Int?) : ResolveResult
        data class Missing(val detail: String) : ResolveResult
    }
}
