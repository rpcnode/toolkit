package rpcnode.toolkit.chains.etc.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.etc.infrastructure.EtcClusters
import rpcnode.toolkit.chains.etc.infrastructure.EtcHostBinaries
import rpcnode.toolkit.chains.etc.infrastructure.EtcPortTable
import rpcnode.toolkit.chains.etc.infrastructure.EtcUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/** Ethereum Classic start under [nodeDir]: unzip core-geth → unit with the catalog ports → systemd. */
class EtcNodeProcessStarter : HostNodeProcessStarter
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
        val envId = EtcClusters.lookup(env).env
        val ports = EtcPortTable.forEnv(envId)
        val archive = launch.args.any { it == "--toolkit-archive=1" }
        val datadir = argPath(launch.args, "--toolkit-datadir=")
            ?: root.resolve(EtcUnitBodies.DATADIR).toAbsolutePath().normalize()

        val bin = when (val ensured = EtcHostBinaries.ensure(root))
        {
            is EtcHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is EtcHostBinaries.Result.Ok -> ensured.node
        }
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
        val body = EtcUnitBodies.unit(
            env = envId,
            nodeDir = root.toAbsolutePath().normalize().toString(),
            bin = bin.toAbsolutePath().normalize().toString(),
            datadir = datadir.toString(),
            httpPort = ports.http,
            wsPort = ports.ws,
            p2pPort = ports.p2p,
            archive = archive,
            logFile = root.resolve(EtcUnitBodies.RELATIVE_LOG).toAbsolutePath().normalize().toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = launch.copy(entry = "bin/${EtcHostBinaries.NODE}"),
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
}
