package rpcnode.toolkit.agent.application.client

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.Yaml
import rpcnode.toolkit.agent.application.enroll.PanelEnrollmentStore
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreRpcAuth
import rpcnode.toolkit.nodes.application.config.EnsureClientConfigDirectories
import rpcnode.toolkit.nodes.infrastructure.host.EnsureHostJava

data class ClientSyncCommand(
    val network: String,
    val env: String,
    val nodeDir: String,
    val configAssignments: Map<String, String> = emptyMap(),
    val configFormat: String = "hoocon",
    val configFile: String? = null,
    val configIniSection: String? = null,
    val configOmitIniKeys: Set<String> = emptySet(),
)

sealed interface ClientSyncResult
{
    data class Ok(val nodeDir: String, val files: List<String>, val configPath: String?) : ClientSyncResult
    data object MissingPanelUrl : ClientSyncResult
    data object InvalidNodeDir : ClientSyncResult
    data class PlanMissing(val detail: String) : ClientSyncResult
    data class DownloadFailed(val detail: String) : ClientSyncResult
    data class PatchFailed(val detail: String) : ClientSyncResult
}

/**
 * Fetches install-plan.yml from the enrolled panel, downloads listed files into [ClientSyncCommand.nodeDir],
 * then patches the config file with API assignments. Does not start any node process.
 */
class SyncClientFromPanelUseCase(
    private val enrollment: PanelEnrollmentStore,
    private val panelUrlOverride: String? = System.getenv("PANEL_URL"),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build(),
    private val timeout: Duration = Duration.ofMinutes(10),
    private val patchConfig: (
        format: String,
        template: String,
        assignments: Map<String, String>,
        iniSection: String?,
        omitIniKeys: Set<String>,
    ) -> String,
)
{
    suspend operator fun invoke(cmd: ClientSyncCommand): ClientSyncResult
    {
        val nodeDir = cmd.nodeDir.trim()
        if (nodeDir.isEmpty() || !nodeDir.startsWith("/") || nodeDir.contains(".."))
        {
            return ClientSyncResult.InvalidNodeDir
        }
        val panelUrl = panelUrlOverride?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: enrollment.read()?.panelUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
            ?: return ClientSyncResult.MissingPanelUrl

        return withContext(Dispatchers.IO) {
            syncBlocking(panelUrl, cmd, nodeDir)
        }
    }

    private fun syncBlocking(panelUrl: String, cmd: ClientSyncCommand, nodeDir: String): ClientSyncResult
    {
        val network = cmd.network.trim().lowercase()
        val env = cmd.env.trim().lowercase()
        val planUrl = "$panelUrl/install/clients/$network/$env/install-plan.yml"
        val planBody = getText(planUrl) ?: return ClientSyncResult.PlanMissing("GET $planUrl failed")
        val plan = parsePlan(planBody) ?: return ClientSyncResult.PlanMissing("invalid install-plan.yml")

        val hostArch = hostArch()
        val files = plan.files.filter { f ->
            f.arch.isNullOrBlank() || f.arch.equals(hostArch, ignoreCase = true)
        }
        if (files.isEmpty())
        {
            return ClientSyncResult.PlanMissing("no files for arch $hostArch in install-plan.yml")
        }

        plan.javaMajor?.let { major ->
            try
            {
                EnsureHostJava.ensure(major)
            }
            catch (e: Exception)
            {
                return ClientSyncResult.DownloadFailed(
                    e.message?.trim()?.ifBlank { null }
                        ?: "Java $major required for this client but could not be installed",
                )
            }
        }

        val destRoot = Path.of(nodeDir)
        try
        {
            Files.createDirectories(destRoot)
        }
        catch (e: Exception)
        {
            return ClientSyncResult.DownloadFailed(e.message ?: "mkdir failed")
        }

        val saved = mutableListOf<String>()
        for (f in files)
        {
            val name = f.path.trim().trimStart('/')
            if (name.isEmpty() || name.contains("..") || name.contains('/'))
            {
                return ClientSyncResult.DownloadFailed("invalid file path in plan: ${f.path}")
            }
            val url = "$panelUrl/install/clients/$network/$env/$name"
            val bytes = getBytes(url)
                ?: return ClientSyncResult.DownloadFailed("GET $url failed")
            val dest = destRoot.resolve(name)
            try
            {
                Files.write(dest, bytes)
                saved += dest.toString()
            }
            catch (e: Exception)
            {
                return ClientSyncResult.DownloadFailed(e.message ?: "write $name failed")
            }
        }

        // VERSION is written beside install-plan on the panel but is not always listed in
        // plan.files — pull it so hooks can report the running client version.
        if (files.none { it.path.equals("VERSION", ignoreCase = true) })
        {
            val versionUrl = "$panelUrl/install/clients/$network/$env/VERSION"
            val versionBytes = getBytes(versionUrl)
            if (versionBytes != null)
            {
                try
                {
                    val dest = destRoot.resolve("VERSION")
                    Files.write(dest, versionBytes)
                    saved += dest.toString()
                }
                catch (_: Exception)
                {
                    // best-effort — start still works without VERSION on disk
                }
            }
        }

        if (cmd.configAssignments.isNotEmpty())
        {
            try
            {
                EnsureClientConfigDirectories.create(cmd.configAssignments)
            }
            catch (e: Exception)
            {
                return ClientSyncResult.PatchFailed(e.message ?: "mkdir from config assignments failed")
            }
        }

        val flagsOnly = cmd.configFormat.trim().lowercase() == "flags"
        val configName = if (flagsOnly)
        {
            null
        }
        else
        {
            cmd.configFile?.trim()?.takeIf { it.isNotEmpty() }
                ?: files.firstOrNull { it.role.equals("config", ignoreCase = true) }?.path
        }
        var configPath: String? = null
        val patchAssignments = mergeBitcoreRpcAuth(
            network = network,
            env = env,
            nodeDir = destRoot,
            configName = configName,
            assignments = cmd.configAssignments,
        )
        if (configName != null && patchAssignments.isNotEmpty())
        {
            val conf = destRoot.resolve(configName.trim().trimStart('/'))
            if (!Files.isRegularFile(conf))
            {
                return ClientSyncResult.PatchFailed("config not found after download: $configName")
            }
            try
            {
                val raw = Files.readString(conf)
                val patched = patchConfig(
                    cmd.configFormat,
                    raw,
                    patchAssignments,
                    cmd.configIniSection,
                    cmd.configOmitIniKeys,
                )
                Files.writeString(conf, patched)
                configPath = conf.toString()
            }
            catch (e: Exception)
            {
                return ClientSyncResult.PatchFailed(e.message ?: "patch failed")
            }
        }
        else if (configName != null)
        {
            configPath = destRoot.resolve(configName.trim().trimStart('/')).toString()
        }

        return ClientSyncResult.Ok(nodeDir = nodeDir, files = saved, configPath = configPath)
    }

    /**
     * Dash / LTC / Doge / BCH: ensure rpcuser/rpcpassword in the patched conf and
     * `.toolkit/rpc-auth.env` (Go leaf proxy reads BITCOIN_RPC_* from toolkit.env).
     */
    private fun mergeBitcoreRpcAuth(
        network: String,
        env: String,
        nodeDir: Path,
        configName: String?,
        assignments: Map<String, String>,
    ): Map<String, String>
    {
        if (!BitcoreRpcAuth.needsRpcUserPassword(network) || configName.isNullOrBlank())
        {
            return assignments
        }
        val conf = nodeDir.resolve(configName.trim().trimStart('/'))
        val existing = if (Files.isRegularFile(conf))
        {
            runCatching { Files.readString(conf) }.getOrNull()
        }
        else
        {
            null
        }
        val creds = BitcoreRpcAuth.ensure(nodeDir, existing)
        BitcoreRpcAuth.upsertGoToolkitEnv(network, env, creds)
        return assignments + BitcoreRpcAuth.assignments(creds)
    }

    private fun getText(url: String): String?
    {
        return try
        {
            val resp = http.send(
                HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            if (resp.statusCode() in 200 until 300) resp.body() else null
        }
        catch (_: Exception)
        {
            null
        }
    }

    private fun getBytes(url: String): ByteArray?
    {
        return try
        {
            val resp = http.send(
                HttpRequest.newBuilder(URI(url)).timeout(timeout).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray(),
            )
            if (resp.statusCode() in 200 until 300) resp.body() else null
        }
        catch (_: Exception)
        {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parsePlan(body: String): ParsedPlan?
    {
        val root = runCatching { Yaml().load<Any>(body) as? Map<*, *> }.getOrNull() ?: return null
        val filesRaw = root["files"] as? List<*> ?: return null
        val files = filesRaw.mapNotNull { entry ->
            val m = entry as? Map<*, *> ?: return@mapNotNull null
            val path = (m["path"] as? String)?.trim().orEmpty()
            if (path.isEmpty()) return@mapNotNull null
            ParsedFile(
                role = (m["role"] as? String)?.trim().orEmpty().ifEmpty { "artifact" },
                path = path,
                arch = (m["arch"] as? String)?.trim()?.ifEmpty { null },
            )
        }
        if (files.isEmpty()) return null
        val launch = root["launch"] as? Map<*, *>
        val javaMajor = (launch?.get("java_major") as? Number)?.toInt()
            ?: (launch?.get("java_major") as? String)?.trim()?.toIntOrNull()
            ?: (launch?.get("javaMajor") as? Number)?.toInt()
            ?: (launch?.get("javaMajor") as? String)?.trim()?.toIntOrNull()
        return ParsedPlan(files = files, javaMajor = javaMajor?.takeIf { it in 1..99 })
    }

    private fun hostArch(): String
    {
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty()
        return if (arch.contains("aarch64") || arch.contains("arm64")) "aarch64" else "x86_64"
    }

    private data class ParsedPlan(val files: List<ParsedFile>, val javaMajor: Int? = null)
    private data class ParsedFile(val role: String, val path: String, val arch: String?)
}
