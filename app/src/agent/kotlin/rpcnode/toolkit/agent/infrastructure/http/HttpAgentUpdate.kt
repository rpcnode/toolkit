package rpcnode.toolkit.agent.infrastructure.http

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.agent.application.update.AgentInstallResult
import rpcnode.toolkit.agent.application.update.AgentJarInstaller
import rpcnode.toolkit.agent.application.update.AgentReleaseChannel
import rpcnode.toolkit.agent.application.update.AgentRestarter
import rpcnode.toolkit.agent.infrastructure.log.HttpIoLog

class HttpAgentReleaseChannel(
    private val timeout: Duration = Duration.ofSeconds(15),
) : AgentReleaseChannel
{
    override suspend fun version(panelUrl: String): String? = withContext(Dispatchers.IO) {
        val url = "${panelUrl.trimEnd('/')}/install/version"
        val started = System.nanoTime()
        val client = HttpClient.newBuilder().connectTimeout(timeout).build()
        val req = HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build()
        val resp = runCatching { client.send(req, HttpResponse.BodyHandlers.ofString()) }.getOrNull()
        if (resp == null)
        {
            HttpIoLog.outbound("GET", url, 0, (System.nanoTime() - started) / 1_000_000)
            return@withContext null
        }
        HttpIoLog.outbound("GET", url, resp.statusCode(), (System.nanoTime() - started) / 1_000_000)
        if (resp.statusCode() !in 200 until 300)
        {
            return@withContext null
        }
        resp.body().trim().ifEmpty { null }
    }
}

class HttpAgentJarInstaller(
    private val dest: Path = defaultAgentJar(),
    private val timeout: Duration = Duration.ofSeconds(60),
) : AgentJarInstaller
{
    override suspend fun install(panelUrl: String): AgentInstallResult = withContext(Dispatchers.IO) {
        val url = "${panelUrl.trimEnd('/')}/install/binaries/rpcnode-agent.jar"
        val parent = dest.parent ?: return@withContext AgentInstallResult.Failed("no jar directory")
        runCatching { Files.createDirectories(parent) }.onFailure {
            return@withContext AgentInstallResult.Failed(it.message ?: "mkdir $parent")
        }
        val tmp = dest.resolveSibling("${dest.fileName}.tmp")
        val started = System.nanoTime()
        val client = HttpClient.newBuilder().connectTimeout(timeout).build()
        val req = HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build()
        val resp = runCatching { client.send(req, HttpResponse.BodyHandlers.ofInputStream()) }.getOrElse {
            HttpIoLog.outbound("GET", url, 0, (System.nanoTime() - started) / 1_000_000, it.message)
            return@withContext AgentInstallResult.Failed(it.message ?: "download failed")
        }
        HttpIoLog.outbound("GET", url, resp.statusCode(), (System.nanoTime() - started) / 1_000_000)
        if (resp.statusCode() !in 200 until 300)
        {
            return@withContext AgentInstallResult.Failed("HTTP ${resp.statusCode()} $url")
        }
        runCatching {
            resp.body().use { input ->
                Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
            }
            runCatching {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure {
            runCatching { Files.deleteIfExists(tmp) }
            return@withContext AgentInstallResult.Failed(it.message ?: "write jar")
        }
        AgentInstallResult.Ok(dest.toString())
    }
}

class SystemdAgentRestarter(
    private val unit: String = System.getenv("RPCNODE_AGENT_UNIT")?.trim().orEmpty()
        .ifEmpty { "rpcnode-agent.service" },
) : AgentRestarter
{
    override fun schedule()
    {
        Thread({
            Thread.sleep(600)
            runCatching {
                ProcessBuilder("systemctl", "restart", unit).redirectErrorStream(true).start()
            }
        }, "rpcnode-agent-restart").apply { isDaemon = true; start() }
    }
}

internal fun defaultAgentJar(): Path
{
    val home = System.getenv("RPCNODE_AGENT_HOME")?.trim()?.ifEmpty { null }
        ?: System.getenv("CHAIN_AGENT_HOME")?.trim()?.ifEmpty { null }
        ?: "/opt/rpcnode"
    return Path.of(home).resolve("lib").resolve("rpcnode-agent.jar")
}
