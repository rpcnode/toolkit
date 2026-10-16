package rpcnode.toolkit.chains.xai.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import rpcnode.toolkit.chains.arb.infrastructure.ArbHostBinaries
import rpcnode.toolkit.chains.xai.infrastructure.XaiChain
import rpcnode.toolkit.chains.xai.infrastructure.XaiParentChain
import rpcnode.toolkit.chains.xai.infrastructure.XaiPortTable
import rpcnode.toolkit.chains.xai.infrastructure.XaiUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Single-unit Nitro start for Xai. Expects the `nitro-*.tar.gz` that the clients sync put into [nodeDir] (the same tarball
 * as Arbitrum's, so [ArbHostBinaries] unpacks it). The chain-info JSON is a classpath resource written under `.toolkit/`;
 * the Arbitrum One RPC goes to a 0600 env file and is kept out of the unit, the process arguments and launch.json.
 */
class XaiNodeProcessStarter : HostNodeProcessStarter
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
        val envId = XaiChain.normalizeEnv(env)
        val ports = XaiPortTable.forEnv(envId)

        val bins = when (val ensured = ArbHostBinaries.ensure(root))
        {
            is ArbHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is ArbHostBinaries.Result.Ok -> ensured.bins
        }

        // a restart replays launch.json, which carries no URL (see below): fall back to what the last Start stored
        val rpc = argValue(launch.args, L1_ARG).ifEmpty { XaiParentChain.readEnvFile(root).orEmpty() }
            .ifEmpty { XaiParentChain.resolve(null) }
        XaiParentChain.validate(rpc)?.let { return HostNodeStartResult.Failed(it) }

        val datadir = argPath(launch.args, "--toolkit-execution=") ?: root.toAbsolutePath().normalize()
        val chainInfo = root.resolve(XaiChain.CHAIN_INFO_FILE).toAbsolutePath().normalize()
        val envFile = try
        {
            Files.createDirectories(datadir)
            Files.createDirectories(root.resolve("logs"))
            writeChainInfo(chainInfo)
            XaiParentChain.writeEnvFile(root, rpc)
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "prepare failed")
        }

        val body = XaiUnitBodies.nitro(
            bin = bins.nitro.toAbsolutePath().toString(),
            datadir = datadir.toString(),
            chainInfo = chainInfo.toString(),
            envFile = envFile.toAbsolutePath().toString(),
            ports = ports,
            wasmRoots = bins.wasmRoots,
            logFile = root.resolve(XaiUnitBodies.RELATIVE_LOG).toAbsolutePath().toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            // launch.json is world-readable: the (possibly keyed) parent RPC stays in the 0600 env file only
            launch = launch.copy(entry = "bin/nitro", args = launch.args.filterNot { it.startsWith(L1_ARG) }),
            startCompanionsFirst = false,
        )
    }

    private fun writeChainInfo(target: Path)
    {
        val bytes = XaiNodeProcessStarter::class.java.classLoader.getResourceAsStream(XaiChain.CHAIN_INFO_RESOURCE)
            ?.use { it.readBytes() }
            ?: error("classpath resource ${XaiChain.CHAIN_INFO_RESOURCE} missing")
        Files.createDirectories(target.parent)
        val tmp = target.resolveSibling(target.fileName.toString() + ".tmp")
        Files.write(tmp, bytes)
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
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
