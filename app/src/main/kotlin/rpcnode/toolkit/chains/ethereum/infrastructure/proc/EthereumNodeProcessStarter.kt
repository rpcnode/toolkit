package rpcnode.toolkit.chains.ethereum.infrastructure.proc

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import rpcnode.toolkit.chains.ethereum.infrastructure.EthereumClusters
import rpcnode.toolkit.chains.ethereum.infrastructure.EthereumPortTable
import rpcnode.toolkit.chains.ethereum.infrastructure.EthereumUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Dual-unit start: geth (primary `rpcnode-ethereum-<env>`) + lighthouse companion.
 * Expects geth/lighthouse tarballs already synced into [nodeDir] (execution role leaf).
 */
class EthereumNodeProcessStarter : HostNodeProcessStarter
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
        val ports = EthereumPortTable.forEnv(envId)
        val cluster = EthereumClusters.lookup(envId)

        val gethBin = ensureBinary(root, "geth", "geth-linux-*.tar.gz")
            ?: return HostNodeStartResult.Failed("geth binary missing under $root (sync geth client first)")
        val lhBin = ensureBinary(root, "lighthouse", "lighthouse-*.tar.gz")
            ?: return HostNodeStartResult.Failed("lighthouse binary missing under $root (sync lighthouse client first)")

        val gethData = argPath(launch.args, "--toolkit-execution=")
            ?: root.resolve("datadir").toAbsolutePath().normalize()
        val lhData = argPath(launch.args, "--toolkit-consensus=")
            ?: lighthouseDatadir(root.toAbsolutePath().normalize())
        val jwtPath = Path.of("/etc/ethereum", envId, "jwt.hex")
        when (val jwt = ensureJwt(jwtPath))
        {
            is JwtResult.Failed -> return HostNodeStartResult.Failed(jwt.detail)
            JwtResult.Ok -> Unit
        }
        try
        {
            Files.createDirectories(gethData)
            Files.createDirectories(lhData)
            Files.createDirectories(root.resolve("logs"))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir datadirs failed")
        }

        val logFile = root.resolve("logs/node.out").toAbsolutePath().toString()
        val primaryUnit = HostNodeLaunchSupport.unitName("ethereum", envId)
        val lhUnit = EthereumUnitBodies.lighthouseUnitName(envId)
        val gethBody = EthereumUnitBodies.geth(
            env = envId,
            bin = gethBin.toAbsolutePath().toString(),
            datadir = gethData.toString(),
            jwtPath = jwtPath.toString(),
            rpcPort = ports.http,
            p2pPort = ports.p2p,
            enginePort = ports.engine,
            cluster = cluster,
            archive = archive,
            logFile = logFile,
        )
        val lhBody = EthereumUnitBodies.lighthouse(
            env = envId,
            bin = lhBin.toAbsolutePath().toString(),
            datadir = lhData.toString(),
            jwtPath = jwtPath.toString(),
            enginePort = ports.engine,
            beaconPort = ports.beacon,
            consensusP2p = ports.consensusP2p,
            cluster = cluster,
            gethUnit = primaryUnit,
            logFile = root.resolve("logs/lighthouse.out").toAbsolutePath().toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = gethBody,
            companions = listOf(lhUnit to lhBody),
            launch = launch.copy(entry = "geth"),
        )
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

    private fun lighthouseDatadir(gethData: Path): Path
    {
        val parent = gethData.parent
        if (parent != null && gethData.fileName.toString().equals("geth", ignoreCase = true))
        {
            return parent.resolve("lighthouse")
        }
        if (parent != null)
        {
            return parent.resolve("lighthouse")
        }
        return gethData.resolveSibling("lighthouse")
    }

    private fun ensureBinary(nodeDir: Path, name: String, archiveGlob: String): Path?
    {
        val direct = nodeDir.resolve(name)
        if (Files.isRegularFile(direct) && Files.isExecutable(direct))
        {
            return direct
        }
        val nested = nodeDir.resolve("bin").resolve(name)
        if (Files.isRegularFile(nested) && Files.isExecutable(nested))
        {
            return nested
        }
        extractMatching(nodeDir, archiveGlob)
        if (Files.isRegularFile(direct) && Files.isExecutable(direct))
        {
            return direct
        }
        val found = findNamed(nodeDir, name) ?: return null
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

    private fun extractMatching(nodeDir: Path, glob: String)
    {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        val archive = Files.list(nodeDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }
                .findFirst()
                .orElse(null)
        } ?: return
        val pb = ProcessBuilder("tar", "-xzf", archive.fileName.toString())
        pb.directory(nodeDir.toFile())
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.waitFor()
    }

    private fun findNamed(root: Path, name: String): Path?
    {
        if (!Files.isDirectory(root))
        {
            return null
        }
        return Files.walk(root, 4).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == name }
                .findFirst()
                .orElse(null)
        }
    }

    private sealed interface JwtResult
    {
        data object Ok : JwtResult
        data class Failed(val detail: String) : JwtResult
    }

    private fun ensureJwt(path: Path): JwtResult
    {
        if (Files.isRegularFile(path))
        {
            return JwtResult.Ok
        }
        return try
        {
            Files.createDirectories(path.parent)
            val pb = ProcessBuilder("openssl", "rand", "-hex", "32")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().trim()
            val code = p.waitFor()
            if (code != 0 || out.length < 32)
            {
                return JwtResult.Failed("openssl rand jwt failed: $out")
            }
            Files.writeString(path, "$out\n")
            JwtResult.Ok
        }
        catch (e: Exception)
        {
            JwtResult.Failed(e.message ?: "jwt create failed")
        }
    }
}
