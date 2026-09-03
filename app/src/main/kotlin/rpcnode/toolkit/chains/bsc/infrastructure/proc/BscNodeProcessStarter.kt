package rpcnode.toolkit.chains.bsc.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream
import rpcnode.toolkit.chains.bsc.infrastructure.BscClusters
import rpcnode.toolkit.chains.bsc.infrastructure.BscPortTable
import rpcnode.toolkit.chains.bsc.infrastructure.BscUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Single-unit start: bsc-geth as `rpcnode-bsc-<env>`.
 * Expects `geth_linux` (+ env zip) already synced into [nodeDir] (chaindata role leaf).
 */
class BscNodeProcessStarter : HostNodeProcessStarter
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
        val envId = BscClusters.lookup(env).env
        val ports = BscPortTable.forEnv(envId)
        val cluster = BscClusters.lookup(envId)

        val gethBin = ensureGeth(root)
            ?: return HostNodeStartResult.Failed("geth binary missing under $root (sync geth client first)")

        val datadir = argPath(launch.args, "--toolkit-datadir=")
            ?: root.toAbsolutePath().normalize()
        try
        {
            Files.createDirectories(datadir)
            Files.createDirectories(root.resolve("logs"))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir datadir failed")
        }

        val etc = Path.of("/etc/bsc", envId)
        when (val genesis = ensureGenesis(root, etc, cluster.zipAsset))
        {
            is GenesisResult.Failed -> return HostNodeStartResult.Failed(genesis.detail)
            is GenesisResult.Ok ->
            {
                when (val init = ensureDatadirInit(gethBin, datadir, genesis.genesisPath))
                {
                    is GenesisResult.Failed -> return HostNodeStartResult.Failed(init.detail)
                    is GenesisResult.Ok -> Unit
                }
                val logFile = root.resolve("logs/node.out").toAbsolutePath().toString()
                val body = BscUnitBodies.geth(
                    env = envId,
                    bin = gethBin.toAbsolutePath().toString(),
                    datadir = datadir.toString(),
                    configPath = genesis.configPath,
                    rpcPort = ports.http,
                    p2pPort = ports.p2p,
                    cacheMb = BscClusters.cacheMb(envId),
                    logFile = logFile,
                )
                return HostNodeLaunchSupport.installCustomUnits(
                    nodeId = nodeId,
                    network = network,
                    env = envId,
                    nodeDir = root,
                    primaryBody = body,
                    companions = emptyList(),
                    launch = launch.copy(entry = "geth"),
                )
            }
        }
    }

    private fun argPath(args: List<String>, prefix: String): Path?
    {
        val raw = args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()
        if (raw.isEmpty() || !raw.startsWith("/") || raw.contains(".."))
        {
            return null
        }
        return Path.of(raw).toAbsolutePath().normalize()
    }

    private fun ensureGeth(nodeDir: Path): Path?
    {
        val direct = nodeDir.resolve("geth")
        if (Files.isRegularFile(direct) && Files.isExecutable(direct))
        {
            return direct
        }
        val candidates = listOf(
            nodeDir.resolve("geth_linux"),
            nodeDir.resolve("bin").resolve("geth"),
            nodeDir.resolve("bin").resolve("geth_linux"),
        )
        for (src in candidates)
        {
            if (!Files.isRegularFile(src))
            {
                continue
            }
            return try
            {
                Files.copy(src, direct, StandardCopyOption.REPLACE_EXISTING)
                direct.toFile().setExecutable(true)
                direct
            }
            catch (_: Exception)
            {
                src.toFile().setExecutable(true)
                src
            }
        }
        return null
    }

    private sealed interface GenesisResult
    {
        data class Ok(val genesisPath: String, val configPath: String?) : GenesisResult
        data class Failed(val detail: String) : GenesisResult
    }

    private fun ensureGenesis(nodeDir: Path, etc: Path, zipName: String): GenesisResult
    {
        val genesisPath = etc.resolve("genesis.json")
        val configPath = etc.resolve("config.toml")
        if (Files.isRegularFile(genesisPath))
        {
            return GenesisResult.Ok(
                genesisPath = genesisPath.toString(),
                configPath = configPath.takeIf { Files.isRegularFile(it) }?.toString(),
            )
        }
        val zip = nodeDir.resolve(zipName).takeIf { Files.isRegularFile(it) }
            ?: findNamed(nodeDir, zipName)
            ?: return GenesisResult.Failed("$zipName missing under $nodeDir (sync geth client first)")
        return try
        {
            Files.createDirectories(etc)
            val tmp = etc.resolve(".tmp-bsc-cfg")
            Files.createDirectories(tmp)
            unzip(zip, tmp)
            val foundGenesis = findNamed(tmp, "genesis.json")
                ?: return GenesisResult.Failed("genesis.json missing in $zipName")
            Files.copy(foundGenesis, genesisPath, StandardCopyOption.REPLACE_EXISTING)
            val foundConfig = findNamed(tmp, "config.toml")
            if (foundConfig != null)
            {
                Files.copy(foundConfig, configPath, StandardCopyOption.REPLACE_EXISTING)
            }
            GenesisResult.Ok(
                genesisPath = genesisPath.toString(),
                configPath = configPath.takeIf { Files.isRegularFile(it) }?.toString(),
            )
        }
        catch (e: Exception)
        {
            GenesisResult.Failed(e.message ?: "extract $zipName failed")
        }
    }

    private fun ensureDatadirInit(gethBin: Path, datadir: Path, genesisPath: String): GenesisResult
    {
        val chaindata = datadir.resolve("geth").resolve("chaindata")
        val lock = datadir.resolve("geth").resolve("LOCK")
        if (Files.isDirectory(chaindata) || Files.exists(lock))
        {
            return GenesisResult.Ok(genesisPath, null)
        }
        return try
        {
            Files.createDirectories(datadir)
            val pb = ProcessBuilder(
                gethBin.toAbsolutePath().toString(),
                "--datadir", datadir.toString(),
                "init", genesisPath,
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code != 0)
            {
                return GenesisResult.Failed("bsc-geth init failed: ${out.trim()}")
            }
            GenesisResult.Ok(genesisPath, null)
        }
        catch (e: Exception)
        {
            GenesisResult.Failed(e.message ?: "bsc-geth init failed")
        }
    }

    private fun unzip(zip: Path, dest: Path)
    {
        ZipInputStream(Files.newInputStream(zip)).use { zis ->
            while (true)
            {
                val entry = zis.nextEntry ?: break
                val out = dest.resolve(entry.name).normalize()
                if (!out.startsWith(dest))
                {
                    continue
                }
                if (entry.isDirectory)
                {
                    Files.createDirectories(out)
                }
                else
                {
                    Files.createDirectories(out.parent)
                    Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING)
                }
                zis.closeEntry()
            }
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
                .filter { Files.isRegularFile(it) && it.fileName.toString().equals(name, ignoreCase = true) }
                .findFirst()
                .orElse(null)
        }
    }
}
