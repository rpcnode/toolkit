package rpcnode.toolkit.chains.starknet.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetChain
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetHostBinaries
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetParentChain
import rpcnode.toolkit.chains.starknet.infrastructure.StarknetUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Single-unit Juno start. Expects the `juno-*.zip` that the clients sync put into [nodeDir]; the snapshot (when chosen) was
 * already streamed into the state directory. The Ethereum L1 WebSocket goes to a 0600 env file and is kept out of the unit,
 * the process arguments and launch.json.
 */
class StarknetNodeProcessStarter : HostNodeProcessStarter
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
        val starknetEnv = StarknetChain.lookup(env)

        val juno = when (val ensured = StarknetHostBinaries.ensure(root))
        {
            is StarknetHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is StarknetHostBinaries.Result.Ok -> ensured.juno
        }

        // a restart replays launch.json, which carries no URL (see below): fall back to what the last Start stored
        val ethNode = argValue(launch.args, L1_ARG).ifEmpty { StarknetParentChain.readEnvFile(root).orEmpty() }
            .ifEmpty { StarknetParentChain.resolve(starknetEnv, null) }
        StarknetParentChain.validate(ethNode)?.let { return HostNodeStartResult.Failed(it) }

        val datadir = argPath(launch.args, "--toolkit-state=") ?: root.toAbsolutePath().normalize()
        val prune = argValue(launch.args, "--toolkit-prune=").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        val envFile = try
        {
            Files.createDirectories(datadir)
            Files.createDirectories(root.resolve("logs"))
            StarknetParentChain.writeEnvFile(root, ethNode)
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "prepare failed")
        }

        val body = StarknetUnitBodies.juno(
            env = starknetEnv,
            bin = juno.toAbsolutePath().toString(),
            nodeDir = root.toAbsolutePath().toString(),
            datadir = datadir.toString(),
            envFile = envFile.toAbsolutePath().toString(),
            pruneWindow = prune,
            logFile = root.resolve(StarknetUnitBodies.RELATIVE_LOG).toAbsolutePath().toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = starknetEnv.id,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            // launch.json is world-readable: the (possibly keyed) L1 URL stays in the 0600 env file only
            launch = launch.copy(entry = "bin/juno", args = launch.args.filterNot { it.startsWith(L1_ARG) }),
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

    private companion object
    {
        const val L1_ARG = "--toolkit-l1-rpc="
    }
}
