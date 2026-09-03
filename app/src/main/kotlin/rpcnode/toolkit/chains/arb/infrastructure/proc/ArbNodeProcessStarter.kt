package rpcnode.toolkit.chains.arb.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.arb.infrastructure.ArbClusters
import rpcnode.toolkit.chains.arb.infrastructure.ArbHostBinaries
import rpcnode.toolkit.chains.arb.infrastructure.ArbL1Parent
import rpcnode.toolkit.chains.arb.infrastructure.ArbPortTable
import rpcnode.toolkit.chains.arb.infrastructure.ArbUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Single-unit Nitro start. Expects CDN `nitro-*.tar.gz` already synced into [nodeDir].
 */
class ArbNodeProcessStarter : HostNodeProcessStarter
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
        val envId = ArbClusters.lookup(env).env
        val cluster = ArbClusters.lookup(envId)
        val ports = ArbPortTable.forEnv(envId)
        val flavor = ArbClusters.normalizeSnapshotFlavor(
            launch.args.firstOrNull { it.startsWith("--toolkit-snapshot=") }
                ?.removePrefix("--toolkit-snapshot="),
        )
        val archive = flavor == "archive"

        val bins = when (val ensured = ArbHostBinaries.ensure(root))
        {
            is ArbHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is ArbHostBinaries.Result.Ok -> ensured.bins
        }

        val l1RpcArg = argValue(launch.args, "--toolkit-l1-rpc=")
        val l1BeaconArg = argValue(launch.args, "--toolkit-l1-beacon=")
        val l1 = when (val parent = ArbL1Parent.resolve(envId, l1RpcArg, l1BeaconArg))
        {
            is ArbL1Parent.Result.Missing -> return HostNodeStartResult.Failed(parent.detail)
            is ArbL1Parent.Result.Ok -> parent.endpoints
        }

        var initUrl = cluster.initUrl
        if (archive)
        {
            val resolved = ArbHostBinaries.resolveArchivePathUrl(envId)
                ?: return HostNodeStartResult.Failed(
                    "arb archive pointer failed — cannot resolve PathDB archive-path URL",
                )
            initUrl = resolved
        }

        val datadir = argPath(launch.args, "--toolkit-execution=")
            ?: root.toAbsolutePath().normalize()
        val toolkitDir = root.resolve(".toolkit")
        val envFile = toolkitDir.resolve("nitro.env")
        try
        {
            Files.createDirectories(datadir)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(toolkitDir)
            Files.writeString(
                envFile,
                ArbUnitBodies.nitroEnv(l1.rpc, l1.beacon, cluster, initUrl, home = datadir.toString()),
            )
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir failed")
        }

        val logFile = root.resolve("logs/node.out").toAbsolutePath().toString()
        val body = ArbUnitBodies.nitro(
            env = envId,
            bin = bins.nitro.toAbsolutePath().toString(),
            datadir = datadir.toString(),
            envFile = envFile.toAbsolutePath().toString(),
            l1Rpc = l1.rpc,
            l1Beacon = l1.beacon,
            cluster = cluster,
            rpcPort = ports.http,
            wsPort = ports.ws,
            wasmRoots = bins.wasmRoots,
            archive = archive,
            initUrl = if (archive) initUrl else "",
            logFile = logFile,
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
