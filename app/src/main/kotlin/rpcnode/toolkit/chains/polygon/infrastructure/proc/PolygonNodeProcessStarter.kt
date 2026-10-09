package rpcnode.toolkit.chains.polygon.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import rpcnode.toolkit.chains.polygon.infrastructure.PolygonClusters
import rpcnode.toolkit.chains.polygon.infrastructure.PolygonPortTable
import rpcnode.toolkit.chains.polygon.infrastructure.PolygonUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Dual-unit start: heimdall companion first, then bor primary `rpcnode-polygon-<env>`.
 * Expects bor/heimdall `.deb` artifacts already synced into [nodeDir] (bor role leaf).
 */
class PolygonNodeProcessStarter : HostNodeProcessStarter
{
    override fun start(
        nodeId: String,
        network: String,
        env: String,
        nodeDir: String,
        launch: NodeLaunchSpec,
    ): HostNodeStartResult
    {
        val root = Path.of(nodeDir.trim())
        if (!Files.isDirectory(root))
        {
            return HostNodeStartResult.Failed("node_dir missing: $root")
        }
        val envId = env.trim().lowercase().ifEmpty { "mainnet" }
        val archive = launch.args.any { it == "--toolkit-archive=1" }
        val ports = PolygonPortTable.forEnv(envId)
        val cluster = PolygonClusters.lookup(envId)
        val ethRpc = argValue(launch.args, "--toolkit-eth-rpc=") ?: cluster.ethRpcUrl

        val borBin = ensureDebBinary(root, "bor", "bor-amd64.deb", "bor-arm64.deb")
            ?: return HostNodeStartResult.Failed("bor binary missing under $root (sync bor client first)")
        val heimdallBin = ensureDebBinary(root, "heimdalld", "heimdall-amd64.deb", "heimdall-arm64.deb")
            ?: return HostNodeStartResult.Failed("heimdalld binary missing under $root (sync heimdall client first)")

        val borData = argPath(launch.args, "--toolkit-bor=")
            ?: root.resolve("datadir").toAbsolutePath().normalize()
        val heimdallHome = argPath(launch.args, "--toolkit-heimdall=")
            ?: heimdallHomeDefault(root.toAbsolutePath().normalize())
        try
        {
            Files.createDirectories(borData)
            Files.createDirectories(heimdallHome)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir datadirs failed")
        }

        val configDeb = if (archive) "bor-archive-config.deb" else "bor-sentry-config.deb"
        when (val cfg = materializeBorConfig(root, borData, configDeb, ports))
        {
            is ConfigResult.Failed -> return HostNodeStartResult.Failed(cfg.detail)
            is ConfigResult.Ok -> Unit
        }
        when (val h = materializeHeimdallHome(root, heimdallHome, ports, ethRpc))
        {
            is ConfigResult.Failed -> return HostNodeStartResult.Failed(h.detail)
            is ConfigResult.Ok -> Unit
        }
        when (val keys = ensureHeimdallKeys(heimdallBin, heimdallHome, cluster.heimdallChainId))
        {
            is ConfigResult.Failed -> return HostNodeStartResult.Failed(keys.detail)
            is ConfigResult.Ok -> Unit
        }

        val borConfig = borData.resolve("config.toml").toAbsolutePath().normalize()
        val logFile = root.resolve("logs/node.out").toAbsolutePath().toString()
        val heimdallUnit = PolygonUnitBodies.heimdallUnitName(envId)
        val borBody = PolygonUnitBodies.bor(
            env = envId,
            bin = borBin.toAbsolutePath().toString(),
            configPath = borConfig.toString(),
            heimdallUnit = heimdallUnit,
            logFile = logFile,
        )
        val heimdallBody = PolygonUnitBodies.heimdall(
            env = envId,
            bin = heimdallBin.toAbsolutePath().toString(),
            home = heimdallHome.toString(),
            logFile = root.resolve("logs/heimdall.out").toAbsolutePath().toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = borBody,
            companions = listOf(heimdallUnit to heimdallBody),
            launch = launch.copy(entry = "bor"),
            startCompanionsFirst = true,
        )
    }

    private fun argValue(args: List<String>, prefix: String): String?
    {
        val raw = args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()
        return raw.ifEmpty { null }
    }

    private fun argPath(args: List<String>, prefix: String): Path?
    {
        val raw = argValue(args, prefix) ?: return null
        if (!raw.startsWith("/") || raw.contains(".."))
        {
            return null
        }
        return Path.of(raw).toAbsolutePath().normalize()
    }

    private fun heimdallHomeDefault(borRoot: Path): Path
    {
        val parent = borRoot.parent
        if (parent != null)
        {
            return parent.resolve("heimdall")
        }
        return borRoot.resolveSibling("heimdall")
    }

    private fun ensureDebBinary(nodeDir: Path, binaryName: String, vararg debNames: String): Path?
    {
        val direct = nodeDir.resolve(binaryName)
        if (Files.isRegularFile(direct) && Files.isExecutable(direct))
        {
            return direct
        }
        for (debName in debNames)
        {
            val deb = nodeDir.resolve(debName)
            if (!Files.isRegularFile(deb))
            {
                continue
            }
            val extractDir = nodeDir.resolve(".toolkit/deb-$binaryName")
            if (!extractDeb(deb, extractDir))
            {
                continue
            }
            val found = findNamed(extractDir, binaryName) ?: continue
            return try
            {
                Files.copy(found, direct, StandardCopyOption.REPLACE_EXISTING)
                direct.toFile().setExecutable(true)
                direct
            }
            catch (_: Exception)
            {
                found
            }
        }
        return findNamed(nodeDir, binaryName)
    }

    private fun extractDeb(deb: Path, dest: Path): Boolean
    {
        return try
        {
            Files.createDirectories(dest)
            val pb = ProcessBuilder("dpkg-deb", "-x", deb.toAbsolutePath().toString(), dest.toAbsolutePath().toString())
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.waitFor() == 0
        }
        catch (_: Exception)
        {
            false
        }
    }

    private fun findNamed(root: Path, name: String): Path?
    {
        if (!Files.isDirectory(root))
        {
            return null
        }
        return Files.walk(root, 6).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == name }
                .findFirst()
                .orElse(null)
        }
    }

    private sealed interface ConfigResult
    {
        data object Ok : ConfigResult
        data class Failed(val detail: String) : ConfigResult
    }

    private fun materializeBorConfig(
        nodeDir: Path,
        borData: Path,
        configDebName: String,
        ports: rpcnode.toolkit.chains.polygon.infrastructure.PolygonPorts,
    ): ConfigResult
    {
        val destConfig = borData.resolve("config.toml")
        val deb = nodeDir.resolve(configDebName)
        if (Files.isRegularFile(deb))
        {
            val extractDir = nodeDir.resolve(".toolkit/bor-config")
            if (!extractDeb(deb, extractDir))
            {
                return ConfigResult.Failed("dpkg-deb extract failed: $configDebName")
            }
            val found = findNamed(extractDir, "config.toml")
                ?: return ConfigResult.Failed("config.toml missing in $configDebName")
            try
            {
                Files.createDirectories(borData)
                var text = Files.readString(found)
                text = text.replace("/var/lib/bor", borData.toString())
                text = rewriteTomlInt(text, "port", ports.p2p)
                text = rewriteHttpPort(text, ports.http, ports.ws)
                text = PolygonConfigPatch.rewriteHeimdallUrl(text, "http://127.0.0.1:${ports.heimdallApi}")
                Files.writeString(destConfig, text)
            }
            catch (e: Exception)
            {
                return ConfigResult.Failed(e.message ?: "write bor config failed")
            }
        }
        else if (!Files.isRegularFile(destConfig))
        {
            return ConfigResult.Failed("missing $configDebName under $nodeDir")
        }
        else
        {
            // Re-apply catalog ports / heimdall URL on an existing datadir config.
            try
            {
                var text = Files.readString(destConfig)
                text = rewriteHttpPort(text, ports.http, ports.ws)
                text = PolygonConfigPatch.rewriteHeimdallUrl(text, "http://127.0.0.1:${ports.heimdallApi}")
                Files.writeString(destConfig, text)
            }
            catch (e: Exception)
            {
                return ConfigResult.Failed(e.message ?: "patch bor config failed")
            }
        }
        // genesis beside datadir when shipped
        for (name in listOf("genesis-mainnet.json", "genesis-amoy.json"))
        {
            val src = nodeDir.resolve(name)
            if (Files.isRegularFile(src))
            {
                runCatching {
                    Files.copy(src, borData.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
        return ConfigResult.Ok
    }

    private fun materializeHeimdallHome(
        nodeDir: Path,
        home: Path,
        ports: rpcnode.toolkit.chains.polygon.infrastructure.PolygonPorts,
        ethRpc: String,
    ): ConfigResult
    {
        val configDir = home.resolve("config")
        val deb = nodeDir.resolve("heimdall-sentry-config.deb")
        if (Files.isRegularFile(deb))
        {
            val extractDir = nodeDir.resolve(".toolkit/heimdall-config")
            if (!extractDeb(deb, extractDir))
            {
                return ConfigResult.Failed("dpkg-deb extract failed: heimdall-sentry-config.deb")
            }
            try
            {
                Files.createDirectories(configDir)
                Files.walk(extractDir).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach { src ->
                        val rel = extractDir.relativize(src).toString()
                        // Strip leading var/lib/heimdall/ when present
                        val leaf = rel
                            .removePrefix("var/lib/heimdall/")
                            .removePrefix("var/lib/heimdall")
                            .trimStart('/')
                        if (leaf.isEmpty()) return@forEach
                        val dest = home.resolve(leaf)
                        Files.createDirectories(dest.parent)
                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                    }
                }
            }
            catch (e: Exception)
            {
                return ConfigResult.Failed(e.message ?: "heimdall config extract failed")
            }
        }
        val genesisSrc = nodeDir.resolve("heimdall-genesis.json")
        if (Files.isRegularFile(genesisSrc))
        {
            try
            {
                Files.createDirectories(configDir)
                Files.copy(genesisSrc, configDir.resolve("genesis.json"), StandardCopyOption.REPLACE_EXISTING)
            }
            catch (e: Exception)
            {
                return ConfigResult.Failed(e.message ?: "heimdall genesis copy failed")
            }
        }
        if (!Files.isDirectory(configDir) || !Files.isRegularFile(configDir.resolve("genesis.json")))
        {
            return ConfigResult.Failed("heimdall home incomplete under $home (need config + genesis)")
        }
        patchHeimdallConfigs(home, ports, ethRpc)
        return ConfigResult.Ok
    }

    /**
     * Sentry configs from the Polygon package do not ship node identity keys.
     * [heimdalld init] creates `priv_validator_key.json` + `node_key.json` (required to start).
     * Run into a temp home and copy only those files so we keep the official genesis / configs.
     */
    private fun ensureHeimdallKeys(bin: Path, home: Path, heimdallChainId: String): ConfigResult
    {
        val configDir = home.resolve("config")
        val priv = configDir.resolve("priv_validator_key.json")
        val nodeKey = configDir.resolve("node_key.json")
        val state = home.resolve("data").resolve("priv_validator_state.json")
        if (Files.isRegularFile(priv) && Files.isRegularFile(nodeKey) && Files.isRegularFile(state))
        {
            return ConfigResult.Ok
        }
        val tmp = try
        {
            Files.createTempDirectory("heimdall-init-")
        }
        catch (e: Exception)
        {
            return ConfigResult.Failed(e.message ?: "temp dir for heimdall init failed")
        }
        return try
        {
            val pb = ProcessBuilder(
                bin.toAbsolutePath().toString(),
                "init",
                "rpcnode",
                "--chain-id=$heimdallChainId",
                "--home",
                tmp.toAbsolutePath().toString(),
            )
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0)
            {
                return ConfigResult.Failed("heimdalld init failed: ${out.take(500)}")
            }
            Files.createDirectories(configDir)
            for (name in listOf("priv_validator_key.json", "node_key.json"))
            {
                val src = tmp.resolve("config").resolve(name)
                if (!Files.isRegularFile(src))
                {
                    return ConfigResult.Failed("heimdalld init did not create config/$name")
                }
                Files.copy(src, configDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
            }
            // CometBFT refuses to start without data/priv_validator_state.json
            val dataDir = home.resolve("data")
            Files.createDirectories(dataDir)
            val stateSrc = tmp.resolve("data").resolve("priv_validator_state.json")
            val stateDest = dataDir.resolve("priv_validator_state.json")
            if (!Files.isRegularFile(stateDest))
            {
                if (Files.isRegularFile(stateSrc))
                {
                    Files.copy(stateSrc, stateDest, StandardCopyOption.REPLACE_EXISTING)
                }
                else
                {
                    Files.writeString(
                        stateDest,
                        """{"height":"0","round":0,"step":0}""" + "\n",
                    )
                }
            }
            ConfigResult.Ok
        }
        catch (e: Exception)
        {
            ConfigResult.Failed(e.message ?: "heimdalld init failed")
        }
        finally
        {
            runCatching {
                Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun patchHeimdallConfigs(
        home: Path,
        ports: rpcnode.toolkit.chains.polygon.infrastructure.PolygonPorts,
        ethRpc: String,
    )
    {
        val configToml = home.resolve("config/config.toml")
        if (Files.isRegularFile(configToml))
        {
            var text = Files.readString(configToml)
            text = text.replace("/var/lib/heimdall", home.toString())
            text = rewriteTomlInt(text, "laddr", ports.heimdallP2p)
            // CometBFT p2p.laddr / rpc.laddr often embed the port in a URL
            text = text.replace(Regex("""tcp://0\.0\.0\.0:\d+"""), "tcp://0.0.0.0:${ports.heimdallP2p}")
            text = text.replace(Regex("""tcp://127\.0\.0\.1:\d+"""), "tcp://127.0.0.1:${ports.heimdallRpc}")
            Files.writeString(configToml, text)
        }
        for (name in listOf("app.toml", "heimdall-config.toml", "config.yaml"))
        {
            val f = home.resolve("config").resolve(name)
            if (!Files.isRegularFile(f)) continue
            var text = Files.readString(f)
            text = text.replace("/var/lib/heimdall", home.toString())
            text = rewriteEthRpc(text, ethRpc)
            text = rewriteBorRpc(text, "http://127.0.0.1:${ports.http}")
            text = rewriteHeimdallApiListen(text, ports.heimdallApi)
            text = rewriteCometRpcUrl(text, ports.heimdallRpc)
            Files.writeString(f, text)
        }
    }

    private fun rewriteEthRpc(text: String, ethRpc: String): String
    {
        var out = text
        out = out.replace(Regex("""(?m)^(eth_rpc_url\s*=\s*).*$"""), "$1\"$ethRpc\"")
        out = out.replace(Regex("""(?m)^(ethereum_rpc_url\s*=\s*).*$"""), "$1\"$ethRpc\"")
        return out
    }

    private fun rewriteBorRpc(text: String, borRpc: String): String
    {
        return text.replace(Regex("""(?m)^(bor_rpc_url\s*=\s*).*$"""), "$1\"$borRpc\"")
    }

    private fun rewriteHeimdallApiListen(text: String, apiPort: Int): String
    {
        // [api] address = "tcp://0.0.0.0:1317" (or localhost) — do not touch [grpc].
        return text.replace(
            Regex("""(?ms)(\[api\][^\[]*?address\s*=\s*")tcp://[^"]+:\d+(")"""),
            "$1tcp://0.0.0.0:$apiPort$2",
        )
    }

    private fun rewriteCometRpcUrl(text: String, rpcPort: Int): String
    {
        return text.replace(
            Regex("""(?m)^(comet_bft_rpc_url\s*=\s*).*$"""),
            "$1\"http://127.0.0.1:$rpcPort\"",
        )
    }

    private fun rewriteHttpPort(text: String, http: Int, ws: Int): String
    {
        var out = text
        // Prefer jsonrpc.http / jsonrpc.ws sections when present
        out = out.replace(
            Regex("""(?ms)(\[jsonrpc\.http\][^\[]*?port\s*=\s*)\d+"""),
            "$1$http",
        )
        out = out.replace(
            Regex("""(?ms)(\[jsonrpc\.ws\][^\[]*?port\s*=\s*)\d+"""),
            "$1$ws",
        )
        return out
    }

    private fun rewriteTomlInt(text: String, key: String, value: Int): String
    {
        return text.replace(Regex("""(?m)^(\s*$key\s*=\s*)\d+\s*$"""), "$1$value")
    }
}
