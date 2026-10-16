package rpcnode.toolkit.chains.optimism.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import rpcnode.toolkit.chains.arb.infrastructure.ArbL1Parent
import rpcnode.toolkit.chains.optimism.infrastructure.OptimismClusters
import rpcnode.toolkit.chains.optimism.infrastructure.OptimismNodeMode
import rpcnode.toolkit.chains.optimism.infrastructure.OptimismPortTable
import rpcnode.toolkit.chains.optimism.infrastructure.OptimismUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Optimism start under [nodeDir]: write the run script (it builds op-reth and op-node, restores the snapshot and supervises
 * both) and the unit. There is nothing to extract — the binaries are compiled by the script on the host.
 */
class OptimismNodeProcessStarter : HostNodeProcessStarter
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
        val envId = OptimismClusters.normalizeEnv(env)
        val ports = OptimismPortTable.forEnv(envId)
        val mode = argValue(launch.args, "--toolkit-mode=").let { OptimismNodeMode.normalize(it) }
        val l1 = when (val parent = ArbL1Parent.resolve(envId, argValue(launch.args, "--toolkit-l1-rpc="), argValue(launch.args, "--toolkit-l1-beacon=")))
        {
            is ArbL1Parent.Result.Missing -> return HostNodeStartResult.Failed(parent.detail)
            is ArbL1Parent.Result.Ok -> parent.endpoints
        }
        val execution = argPath(launch.args, "--toolkit-execution=") ?: root.resolve("execution").toAbsolutePath().normalize()
        val consensus = argPath(launch.args, "--toolkit-consensus=") ?: root.resolve("consensus").toAbsolutePath().normalize()
        val snapshots = argPath(launch.args, "--toolkit-snapshots=") ?: root.resolve("snapshots").toAbsolutePath().normalize()

        val script = root.resolve(OptimismUnitBodies.RUN_SCRIPT)
        val logPath = root.resolve(OptimismUnitBodies.RELATIVE_LOG)
        try
        {
            Files.createDirectories(execution)
            Files.createDirectories(consensus)
            Files.createDirectories(snapshots)
            Files.createDirectories(script.parent)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            Files.writeString(
                script,
                OptimismUnitBodies.runScript(
                    env = envId,
                    mode = mode,
                    nodeDir = root.toAbsolutePath().normalize().toString(),
                    execDir = execution.toString(),
                    consDir = consensus.toString(),
                    snapDir = snapshots.toString(),
                    stateDir = root.resolve(".toolkit").toAbsolutePath().normalize().toString(),
                    logFile = logPath.toAbsolutePath().normalize().toString(),
                    l1Rpc = l1.rpc,
                    l1Beacon = l1.beacon,
                    ports = ports,
                ),
            )
            makeExecutable(script)
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write optimism run script failed")
        }

        val body = OptimismUnitBodies.unit(
            env = envId,
            nodeDir = root.toAbsolutePath().normalize().toString(),
            execStart = script.toAbsolutePath().normalize().toString(),
            logFile = logPath.toAbsolutePath().normalize().toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = launch.copy(entry = OptimismUnitBodies.RUN_SCRIPT, logFile = OptimismUnitBodies.RELATIVE_LOG),
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

    private fun makeExecutable(path: Path)
    {
        try
        {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms += PosixFilePermission.OWNER_EXECUTE
            perms += PosixFilePermission.GROUP_EXECUTE
            perms += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(path, perms)
        }
        catch (_: Exception)
        {
            path.toFile().setExecutable(true)
        }
    }
}
