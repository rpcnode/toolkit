package rpcnode.toolkit.chains.base.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.base.infrastructure.BaseClusters
import rpcnode.toolkit.chains.base.infrastructure.BaseHostBinaries
import rpcnode.toolkit.chains.base.infrastructure.BaseL1Parent
import rpcnode.toolkit.chains.base.infrastructure.BasePortTable
import rpcnode.toolkit.chains.base.infrastructure.BaseUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Dual-unit start: base-reth-node (primary) + base-consensus companion.
 * Expects GitHub release tarballs already synced into [nodeDir].
 */
class BaseNodeProcessStarter : HostNodeProcessStarter
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
        val envId = BaseClusters.lookup(env).env
        val cluster = BaseClusters.lookup(envId)
        val ports = BasePortTable.forEnv(envId)

        val bins = when (val ensured = BaseHostBinaries.ensure(envId, root))
        {
            is BaseHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is BaseHostBinaries.Result.Ok -> ensured.bins
        }

        val l1RpcArg = argValue(launch.args, "--toolkit-l1-rpc=")
        val l1BeaconArg = argValue(launch.args, "--toolkit-l1-beacon=")
        val l1 = when (val parent = BaseL1Parent.resolve(envId, l1RpcArg, l1BeaconArg))
        {
            is BaseL1Parent.Result.Missing -> return HostNodeStartResult.Failed(parent.detail)
            is BaseL1Parent.Result.Ok -> parent.endpoints
        }

        val rethData = argPath(launch.args, "--toolkit-execution=")
            ?: root.toAbsolutePath().normalize()
        val etc = Path.of("/etc/base", envId)
        val jwtRaw = when (val jwt = ensureJwt(etc.resolve("jwt.hex")))
        {
            is JwtResult.Failed -> return HostNodeStartResult.Failed(jwt.detail)
            is JwtResult.Ok -> jwt.hex
        }
        val jwtPath = etc.resolve("jwt.hex")
        try
        {
            Files.createDirectories(rethData)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(etc)
            Files.createDirectories(Path.of("/opt/base", envId, "bin"))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir failed")
        }

        val wrapper = Path.of("/opt/base", envId, "bin", "run-base-consensus.sh")
        BaseUnitBodies.writeExecutable(
            wrapper,
            BaseUnitBodies.consensusWrapper(bins.consensus.toAbsolutePath().toString()),
        )
        Files.writeString(
            etc.resolve("consensus.env"),
            BaseUnitBodies.consensusEnv(
                cluster = cluster,
                l1Rpc = l1.rpc,
                l1Beacon = l1.beacon,
                jwtPath = jwtPath.toString(),
                jwtRaw = jwtRaw,
                enginePort = ports.engine,
                consensusP2p = ports.consensusP2p,
            ),
        )

        val logFile = root.resolve("logs/node.out").toAbsolutePath().toString()
        val primaryUnit = HostNodeLaunchSupport.unitName("base", envId)
        val consUnit = BaseUnitBodies.consensusUnitName(envId)
        val rethBody = BaseUnitBodies.reth(
            env = envId,
            bin = bins.reth.toAbsolutePath().toString(),
            datadir = rethData.toString(),
            jwtPath = jwtPath.toString(),
            rpcPort = ports.http,
            wsPort = ports.ws,
            enginePort = ports.engine,
            p2pPort = ports.p2p,
            discoveryV5 = ports.discoveryV5,
            cluster = cluster,
            logFile = logFile,
        )
        val consBody = BaseUnitBodies.consensus(
            env = envId,
            wrapper = wrapper.toAbsolutePath().toString(),
            etc = etc.toString(),
            rethUnit = primaryUnit,
            logFile = root.resolve("logs/consensus.out").toAbsolutePath().toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = rethBody,
            companions = listOf(consUnit to consBody),
            launch = launch.copy(entry = "base-reth-node"),
            startCompanionsFirst = false,
        )
    }

    private fun argValue(args: List<String>, prefix: String): String =
        args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()

    private fun argPath(args: List<String>, prefix: String): Path?
    {
        val raw = argValue(args, prefix)
        if (raw.isEmpty() || !raw.startsWith("/") || raw.contains(".."))
        {
            return null
        }
        return Path.of(raw).toAbsolutePath().normalize()
    }

    private sealed interface JwtResult
    {
        data class Ok(val hex: String) : JwtResult
        data class Failed(val detail: String) : JwtResult
    }

    private fun ensureJwt(path: Path): JwtResult
    {
        if (Files.isRegularFile(path))
        {
            val hex = Files.readString(path).trim()
            if (hex.length < 32)
            {
                return JwtResult.Failed("empty jwt at $path")
            }
            return JwtResult.Ok(hex)
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
            JwtResult.Ok(out)
        }
        catch (e: Exception)
        {
            JwtResult.Failed(e.message ?: "jwt create failed")
        }
    }
}
