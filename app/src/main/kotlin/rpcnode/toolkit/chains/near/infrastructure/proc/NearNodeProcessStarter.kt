package rpcnode.toolkit.chains.near.infrastructure.proc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit
import rpcnode.toolkit.chains.near.infrastructure.NearChains
import rpcnode.toolkit.chains.near.infrastructure.NearConfigPatch
import rpcnode.toolkit.chains.near.infrastructure.NearEnv
import rpcnode.toolkit.chains.near.infrastructure.NearSettings
import rpcnode.toolkit.chains.near.infrastructure.NearUnitBodies
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmHostBinaries
import rpcnode.toolkit.chains.nonevm.infrastructure.ToolkitArgs
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport
import rpcnode.toolkit.nodes.infrastructure.host.NodeProxyEnv
import rpcnode.toolkit.shared.infrastructure.http.HttpProxy

/**
 * NEAR start under `node_dir`: `neard` into `bin/`, `neard init` once per home (genesis + the `rpc` config profile from the
 * official bucket), boot nodes from the public `network_info` (the epoch-sync recipe — a node with an empty `boot_nodes`
 * sits at 0 peers), the keys Start owns in `config.json`, the unit.
 */
class NearNodeProcessStarter(
    private val fetchNetworkInfo: (url: String, proxy: String) -> String? = ::postNetworkInfo,
) : HostNodeProcessStarter
{
    override fun start(
        nodeId: String,
        network: String,
        env: String,
        nodeDir: String,
        launch: NodeLaunchSpec,
    ): HostNodeStartResult
    {
        val root = Path.of(nodeDir.trim()).toAbsolutePath().normalize()
        if (!Files.isDirectory(root))
        {
            return HostNodeStartResult.Failed("node_dir missing: $root")
        }
        if (root.toString().any { it.isWhitespace() })
        {
            return HostNodeStartResult.Failed("node_dir must not contain spaces")
        }
        val e = NearChains.env(env)
        val home = ToolkitArgs.path(launch.args, "state") ?: root
        val bin = when (val r = NonEvmHostBinaries.ensure(root, listOf(BINARY), NonEvmHostBinaries.Source.Raw))
        {
            is NonEvmHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(r.detail)
            is NonEvmHostBinaries.Result.Ok -> r.bin.resolve(BINARY)
        }
        if (NonEvmHostBinaries.versionOutput(bin) == null)
        {
            return HostNodeStartResult.Failed("neard does not run on this host (wrong CPU architecture or libc) — check `$bin --version`")
        }
        val proxy = NodeProxyEnv.read(root).orEmpty()
        try
        {
            Files.createDirectories(home)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            val config = home.resolve("config.json")
            if (!Files.isRegularFile(config))
            {
                initHome(bin, e, home, proxy)?.let { return HostNodeStartResult.Failed(it) }
            }
            // a RPC node is not a validator: never leave a validator key in the home
            runCatching { Files.deleteIfExists(home.resolve("validator_key.json")) }
            val text = Files.readString(config)
            val fresh = e.publicRpcUrls.asSequence()
                .map { NearConfigPatch.bootNodes(fetchNetworkInfo(it, proxy)) }
                .firstOrNull { it.isNotEmpty() }
                .orEmpty()
            if (fresh.isEmpty() && NearConfigPatch.currentBootNodes(text).isBlank())
            {
                return HostNodeStartResult.Failed(
                    "no NEAR boot nodes: none of ${e.publicRpcUrls.joinToString()} answered network_info and config.json has none — " +
                        "without boot nodes the node stays at 0 peers",
                )
            }
            val patched = NearConfigPatch.apply(text, fresh)
            if (patched != text)
            {
                Files.writeString(config, patched)
            }
        }
        catch (ex: Exception)
        {
            return HostNodeStartResult.Failed(ex.message ?: "prepare NEAR home failed")
        }

        val body = NearUnitBodies.unit(
            env = e,
            nodeDir = root.toString(),
            binary = bin.toString(),
            home = home.toString(),
            logFile = root.resolve(NearSettings.RELATIVE_LOG).toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = e.id,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = launch.copy(entry = "bin/$BINARY", logFile = NearSettings.RELATIVE_LOG),
        )
    }

    /** Null on success, else the failure text. */
    private fun initHome(bin: Path, env: NearEnv, home: Path, proxy: String): String?
    {
        val cmd = NearUnitBodies.initCommand(bin.toString(), env, home.toString())
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        NodeProxyEnv.vars(proxy).forEach { (k, v) -> pb.environment()[k] = v }
        val p = pb.start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        if (!p.waitFor(300, TimeUnit.SECONDS))
        {
            p.destroyForcibly()
            return "neard init timed out"
        }
        return if (p.exitValue() == 0 && Files.isRegularFile(home.resolve("config.json"))) null
        else "neard init failed (exit ${p.exitValue()}): ${out.takeLast(400)}"
    }

    companion object
    {
        const val BINARY = "neard"

        private const val NETWORK_INFO = """{"jsonrpc":"2.0","id":"rpcnode","method":"network_info","params":[]}"""

        fun postNetworkInfo(url: String, proxy: String): String?
        {
            return try
            {
                val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).apply { HttpProxy.apply(this, proxy) }.build()
                val req = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(NETWORK_INFO)).build()
                val resp = client.send(req, HttpResponse.BodyHandlers.ofString())
                if (resp.statusCode() in 200..299) resp.body() else null
            }
            catch (_: Exception)
            {
                null
            }
        }
    }
}
