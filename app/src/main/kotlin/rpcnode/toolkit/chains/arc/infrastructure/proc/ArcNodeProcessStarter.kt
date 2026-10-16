package rpcnode.toolkit.chains.arc.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import rpcnode.toolkit.chains.arc.infrastructure.ArcClusters
import rpcnode.toolkit.chains.arc.infrastructure.ArcHostBinaries
import rpcnode.toolkit.chains.arc.infrastructure.ArcLayout
import rpcnode.toolkit.chains.arc.infrastructure.ArcNodeMode
import rpcnode.toolkit.chains.arc.infrastructure.ArcPortTable
import rpcnode.toolkit.chains.arc.infrastructure.ArcUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Arc start under [nodeDir]: extract the three binaries → run script → unit. The execution and consensus DBs go to the
 * disk roles; the run script does the snapshot bootstrap and supervises both layers.
 */
class ArcNodeProcessStarter : HostNodeProcessStarter
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
        val envId = ArcClusters.normalizeEnv(env)
        val ports = ArcPortTable.forEnv(envId)
        val mode = argValue(launch.args, "--toolkit-mode=")?.let { ArcNodeMode.normalize(it) } ?: ArcNodeMode.FULL
        // the execution role dir holds bin/ logs/ .toolkit/ and the two databases in their own subdirectories
        val base = argPath(launch.args, "--toolkit-execution=") ?: root.toAbsolutePath().normalize()
        val execution = ArcLayout.execution(base)
        val consensus = ArcLayout.consensus(base)

        val binDir = when (val ensured = ArcHostBinaries.ensure(root))
        {
            is ArcHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is ArcHostBinaries.Result.Ok -> ensured.binDir
        }

        val script = root.resolve(ArcUnitBodies.RUN_SCRIPT)
        val logPath = root.resolve(ArcUnitBodies.RELATIVE_LOG)
        try
        {
            Files.createDirectories(execution)
            Files.createDirectories(consensus)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            Files.writeString(
                script,
                ArcUnitBodies.runScript(
                    env = envId,
                    mode = mode,
                    execDir = execution.toString(),
                    consDir = consensus.toString(),
                    binDir = binDir.toAbsolutePath().normalize().toString(),
                    stateDir = root.resolve(".toolkit").toAbsolutePath().normalize().toString(),
                    logFile = logPath.toAbsolutePath().normalize().toString(),
                    ports = ports,
                ),
            )
            makeExecutable(script)
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write arc run script failed")
        }

        val body = ArcUnitBodies.unit(
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
            launch = launch.copy(
                entry = ArcUnitBodies.RUN_SCRIPT,
                logFile = ArcUnitBodies.RELATIVE_LOG,
            ),
        )
    }

    private fun argValue(args: List<String>, prefix: String): String?
    {
        val raw = args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim().orEmpty()
        return raw.takeIf { it.isNotEmpty() }
    }

    private fun argPath(args: List<String>, prefix: String): Path?
    {
        val raw = argValue(args, prefix) ?: return null
        if (!raw.startsWith("/") || raw.contains(".."))
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
