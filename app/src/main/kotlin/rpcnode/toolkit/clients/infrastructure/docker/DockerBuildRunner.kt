package rpcnode.toolkit.clients.infrastructure.docker

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import rpcnode.toolkit.clients.application.ClientBuildResult
import rpcnode.toolkit.clients.application.builtArtifactName
import rpcnode.toolkit.clients.domain.model.ClientBuildSpec

/**
 * One `docker build` of a client Dockerfile for one platform (BuildKit): the release arrives as the `VERSION` / `TAG`
 * build args and the Dockerfile's `FROM scratch` stage is exported with `--output type=local`, so no bind mount and no
 * toolchain outside the build image. Used by the panel (local fallback) and by the host agent (the normal path).
 */
class DockerBuildRunner(
    private val docker: String = System.getenv("DOCKER_BIN")?.takeIf { it.isNotBlank() } ?: "docker",
    private val runner: BuildProcessRunner = ProcessBuildRunner,
    private val timeoutHours: Long = 12,
)
{
    private val log = LoggerFactory.getLogger(DockerBuildRunner::class.java)

    /** Null when `docker build` can run here, otherwise why it cannot. */
    fun problem(): String? = daemonProblem() ?: buildxProblem()

    /** Null when the docker CLI reaches a daemon. */
    fun daemonProblem(): String?
    {
        val probe = runCatching { runner.run(listOf(docker, "version", "--format", "{{.Server.Version}}"), 20) {} }
            .getOrElse { return "docker CLI is not available (${it.message})" }
        if (probe.exitCode != 0)
        {
            return "docker daemon is not reachable: " + probe.tail.takeLast(3).joinToString(" ")
        }
        return null
    }

    /**
     * `--output type=local` (how the binary leaves the image) is a BuildKit feature: without the buildx plugin docker
     * refuses every build. Null when the plugin works.
     */
    fun buildxProblem(): String?
    {
        val buildx = runCatching { runner.run(listOf(docker, "buildx", "version"), 20) {} }.getOrNull()
        if (buildx == null || buildx.exitCode != 0)
        {
            return "the Docker buildx plugin is missing (BuildKit is needed to export the binary) — " +
                "install docker-buildx-plugin (apt) or docker-buildx (dnf) on this host"
        }
        return null
    }

    /** Runs `docker <args>` (used to verify a freshly installed plugin). */
    fun succeeds(vararg args: String): Boolean =
        runCatching { runner.run(listOf(docker) + args, 30) {}.exitCode == 0 }.getOrDefault(false)

    /**
     * Builds [dockerfile] for [platform] and leaves each of [outputs] in [outDir] as `<output>-<arch>`.
     * Blocking and long-running; callers run it off the request thread and interrupt it to cancel.
     */
    fun build(
        dockerfile: String,
        platform: String,
        version: String,
        tag: String,
        outputs: List<String>,
        outDir: Path,
        onLog: (String) -> Unit,
    ): ClientBuildResult
    {
        if (!SAFE_ARG.matches(version) || !SAFE_ARG.matches(tag))
        {
            return ClientBuildResult.Failed("unsafe version/tag for a build argument: '$version' / '$tag'")
        }
        if (platform !in ClientBuildSpec.SUPPORTED_PLATFORMS)
        {
            return ClientBuildResult.Failed("unsupported platform $platform")
        }
        val arch = ClientBuildSpec.archOf(platform)
        val context = Files.createTempDirectory("rpcnode-build-ctx-")
        val exported = Files.createTempDirectory("rpcnode-build-out-")
        try
        {
            Files.createDirectories(outDir)
            Files.writeString(context.resolve("Dockerfile"), dockerfile)
            onLog("docker build $tag for $platform")
            val result = runner.run(buildCommand(platform, version, tag, exported, context), timeoutHours * 3600L, onLog)
            if (result.exitCode != 0)
            {
                val why = if (result.timedOut) "timed out after $timeoutHours h" else "exit ${result.exitCode}:\n" + result.tail.takeLast(12).joinToString("\n")
                return ClientBuildResult.Failed("docker build failed ($why)")
            }
            val names = mutableListOf<String>()
            for (output in outputs)
            {
                val file = exported.resolve(output)
                if (!Files.isRegularFile(file) || Files.size(file) <= 0)
                {
                    return ClientBuildResult.Failed("build finished but $output is missing from the exported files ($platform)")
                }
                val name = builtArtifactName(output, arch)
                val tmp = outDir.resolve(".$name.part")
                Files.copy(file, tmp, StandardCopyOption.REPLACE_EXISTING)
                tmp.toFile().setExecutable(true, false)
                Files.move(tmp, outDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                names += name
            }
            return ClientBuildResult.Built(names)
        }
        catch (e: InterruptedException)
        {
            throw e
        }
        catch (e: Exception)
        {
            log.warn("docker build {} for {} failed", tag, platform, e)
            return ClientBuildResult.Failed(e.message ?: "docker build failed")
        }
        finally
        {
            context.toFile().deleteRecursively()
            exported.toFile().deleteRecursively()
        }
    }

    internal fun buildCommand(platform: String, version: String, tag: String, out: Path, context: Path): List<String> =
        listOf(
            docker, "build",
            "--platform", platform,
            "--build-arg", "VERSION=$version",
            "--build-arg", "TAG=$tag",
            "--output", "type=local,dest=$out",
            "--progress", "plain",
            context.toString(),
        )

    companion object
    {
        private val SAFE_ARG = Regex("[A-Za-z0-9._+][A-Za-z0-9._+/-]*")
    }
}

data class BuildProcessResult(val exitCode: Int, val tail: List<String>, val timedOut: Boolean = false)

/** Runs one process, streams its lines to `onLine` and keeps the last ones for error reports. */
fun interface BuildProcessRunner
{
    fun run(command: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit): BuildProcessResult
}

object ProcessBuildRunner : BuildProcessRunner
{
    private const val TAIL_LINES = 60

    override fun run(command: List<String>, timeoutSeconds: Long, onLine: (String) -> Unit): BuildProcessResult
    {
        val process = ProcessBuilder(command).redirectErrorStream(true).apply {
            environment()["DOCKER_BUILDKIT"] = "1"
        }.start()
        val tail = ArrayDeque<String>()
        val reader = Thread {
            process.inputStream.bufferedReader().forEachLine { line ->
                synchronized(tail) {
                    tail.addLast(line)
                    if (tail.size > TAIL_LINES) tail.removeFirst()
                }
                onLine(line)
            }
        }.apply { isDaemon = true; start() }
        try
        {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            {
                process.destroyForcibly()
                reader.join(2000)
                return BuildProcessResult(-1, synchronized(tail) { tail.toList() }, timedOut = true)
            }
            reader.join(5000)
            return BuildProcessResult(process.exitValue(), synchronized(tail) { tail.toList() })
        }
        catch (e: InterruptedException)
        {
            process.destroyForcibly()
            throw e
        }
    }
}
