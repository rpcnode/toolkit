package rpcnode.toolkit.chains.aleo.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import rpcnode.toolkit.chains.aleo.infrastructure.AleoSettings
import rpcnode.toolkit.chains.aleo.infrastructure.AleoUnitBodies
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmHostBinaries
import rpcnode.toolkit.chains.nonevm.infrastructure.ToolkitArgs
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/** Aleo start: `snarkos` out of the release zip into `bin/`, ledger + node-data dirs (the latter 0700: JWT secret), the unit. */
class AleoNodeProcessStarter : HostNodeProcessStarter
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
        val ledger = ToolkitArgs.path(launch.args, "ledger") ?: root.resolve("ledger")
        val nodeData = ToolkitArgs.path(launch.args, "nodedata") ?: root.resolve("node-data")
        val bin = when (val r = NonEvmHostBinaries.ensure(root, listOf(BINARY), NonEvmHostBinaries.Source.Archive("snarkos-linux-*.zip", NonEvmHostBinaries.ArchiveKind.ZIP)))
        {
            is NonEvmHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(r.detail)
            is NonEvmHostBinaries.Result.Ok -> r.bin.resolve(BINARY)
        }
        try
        {
            Files.createDirectories(ledger)
            Files.createDirectories(nodeData)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            runCatching { Files.setPosixFilePermissions(nodeData, PosixFilePermissions.fromString("rwx------")) }
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir failed")
        }
        val body = AleoUnitBodies.unit(
            nodeDir = root.toString(),
            binary = bin.toString(),
            ledger = ledger.toString(),
            nodeData = nodeData.toString(),
            logFile = root.resolve(AleoSettings.RELATIVE_LOG).toString(),
            stdout = root.resolve(AleoSettings.RELATIVE_STDOUT).toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = "mainnet",
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = launch.copy(entry = "bin/$BINARY", logFile = AleoSettings.RELATIVE_LOG),
        )
    }

    companion object
    {
        const val BINARY = "snarkos"
    }
}
