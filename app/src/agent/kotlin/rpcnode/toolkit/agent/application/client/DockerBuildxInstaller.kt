package rpcnode.toolkit.agent.application.client

import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import org.slf4j.LoggerFactory
import rpcnode.toolkit.agent.infrastructure.proc.runningAsRoot
import rpcnode.toolkit.clients.infrastructure.docker.DockerBuildRunner

/** Makes `docker buildx` available on this host so a client build can run. */
fun interface DockerBuildxInstaller
{
    /** Null once `docker buildx` works, otherwise why it could not be installed. */
    fun install(onLog: (String) -> Unit): String?
}

/**
 * Installs Docker's official buildx CLI plugin from the docker/buildx GitHub release: a pinned version and the SHA-256 of
 * each binary pinned here, so the download is verified without trusting the server it came from. Works on any distribution
 * (the distro packages are named differently, or missing). The binary goes to the system-wide plugin directory Docker
 * reads (`/usr/local/lib/docker/cli-plugins`); nothing else on the host is touched.
 */
class GithubBuildxInstaller(
    private val docker: DockerBuildRunner = DockerBuildRunner(),
    private val isRoot: () -> Boolean = ::runningAsRoot,
    private val pluginDir: Path = Path.of("/usr/local/lib/docker/cli-plugins"),
    private val hostArch: () -> String = { System.getProperty("os.arch").orEmpty().lowercase() },
    private val download: (url: String, dest: Path) -> Unit = ::httpDownload,
    private val checksums: Map<String, String> = SHA256,
) : DockerBuildxInstaller
{
    private val log = LoggerFactory.getLogger(GithubBuildxInstaller::class.java)

    @Synchronized
    override fun install(onLog: (String) -> Unit): String?
    {
        if (docker.buildxProblem() == null)
        {
            return null
        }
        if (!isRoot())
        {
            return "the buildx plugin is missing and the agent is not root, so it cannot install it"
        }
        val arch = if (hostArch().let { it.contains("aarch64") || it.contains("arm64") }) "arm64" else "amd64"
        val sha = checksums[arch] ?: return "no buildx build for $arch"
        val name = "buildx-v$VERSION.linux-$arch"
        val part = pluginDir.resolve("docker-buildx.part")
        val target = pluginDir.resolve("docker-buildx")
        return try
        {
            onLog("installing the Docker buildx plugin v$VERSION ($arch)")
            Files.createDirectories(pluginDir)
            download("https://github.com/docker/buildx/releases/download/v$VERSION/$name", part)
            val got = sha256(part)
            if (got != sha)
            {
                Files.deleteIfExists(part)
                return "downloaded buildx does not match the pinned checksum ($got)"
            }
            part.toFile().setExecutable(true, false)
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING)
            if (!docker.succeeds("buildx", "version"))
            {
                Files.deleteIfExists(target)
                return "buildx was installed to $target but docker does not load it"
            }
            log.info("installed docker buildx v{} into {}", VERSION, pluginDir)
            null
        }
        catch (e: Exception)
        {
            runCatching { Files.deleteIfExists(part) }
            "could not install the buildx plugin: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    companion object
    {
        const val VERSION = "0.19.3"

        /** From the release's checksums.txt. */
        val SHA256 = mapOf(
            "amd64" to "32ed111e941e385c2fb8261eba06a4056915718fd606f8278834ac1931d261a2",
            "arm64" to "138b587399b27bb61945a36d67866177b85dea1155101a2be63c7ab715f18a2e",
        )

        fun sha256(path: Path): String
        {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true)
                {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private fun httpDownload(url: String, dest: Path)
        {
            val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build()
            val resp = client.send(
                HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(10)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream(),
            )
            resp.body().use { input ->
                if (resp.statusCode() !in 200 until 300)
                {
                    throw IOException("HTTP ${resp.statusCode()} for $url")
                }
                Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
