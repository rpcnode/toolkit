package rpcnode.toolkit.chains.robinhood.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.arb.infrastructure.ArbHostBinaries
import rpcnode.toolkit.chains.arb.infrastructure.ArbL1Parent
import rpcnode.toolkit.chains.robinhood.infrastructure.RobinhoodClusters
import rpcnode.toolkit.chains.robinhood.infrastructure.RobinhoodPortTable
import rpcnode.toolkit.chains.robinhood.infrastructure.RobinhoodUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Single-unit Nitro start for Robinhood Chain. Expects the `nitro-*.tar.gz` and the chain-info JSON that the clients sync
 * put into [nodeDir] (the Nitro tarball is the same as Arbitrum's, so [ArbHostBinaries] unpacks it).
 */
class RobinhoodNodeProcessStarter : HostNodeProcessStarter
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
        val envId = RobinhoodClusters.normalizeEnv(env)
        val cluster = RobinhoodClusters.lookup(envId)
        val ports = RobinhoodPortTable.forEnv(envId)
        val snapshot = RobinhoodClusters.normalizeSnapshot(argValue(launch.args, "--toolkit-snapshot="))

        val bins = when (val ensured = ArbHostBinaries.ensure(root))
        {
            is ArbHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is ArbHostBinaries.Result.Ok -> ensured.bins
        }
        val chainInfo = root.resolve(cluster.chainInfoFile)
        if (!Files.isRegularFile(chainInfo) || Files.size(chainInfo) == 0L)
        {
            return HostNodeStartResult.Failed(
                "${cluster.chainInfoFile} missing under $root — sync Robinhood clients (chain-info file) first",
            )
        }
        val l1 = when (val parent = ArbL1Parent.resolve(envId, argValue(launch.args, "--toolkit-l1-rpc="), argValue(launch.args, "--toolkit-l1-beacon=")))
        {
            is ArbL1Parent.Result.Missing -> return HostNodeStartResult.Failed(parent.detail)
            is ArbL1Parent.Result.Ok -> parent.endpoints
        }

        val datadir = argPath(launch.args, "--toolkit-execution=") ?: root.toAbsolutePath().normalize()
        try
        {
            Files.createDirectories(datadir)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir failed")
        }

        val body = RobinhoodUnitBodies.nitro(
            env = envId,
            bin = bins.nitro.toAbsolutePath().toString(),
            datadir = datadir.toString(),
            chainInfo = chainInfo.toAbsolutePath().normalize().toString(),
            l1Rpc = l1.rpc,
            l1Beacon = l1.beacon,
            rpcPort = ports.http,
            wsPort = ports.ws,
            wasmRoots = bins.wasmRoots,
            snapshot = snapshot,
            logFile = root.resolve(RobinhoodUnitBodies.RELATIVE_LOG).toAbsolutePath().toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = launch.copy(entry = "nitro"),
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
}
