package rpcnode.toolkit.chains.opstack.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import rpcnode.toolkit.chains.arb.infrastructure.ArbL1Parent
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackInstallOptions
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackNetworkSpec
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackNodeMode
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackSnapshotUrl
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * OP Stack start under [nodeDir]: write the run script (it installs op-reth / world-chain and op-node, restores the snapshot and
 * supervises both) and the unit. There is nothing to extract here — the script picks up what the panel synced for this CPU.
 * The script holds the L1 endpoint and the operator's snapshot URL, so it is readable by its owner only.
 */
class OpstackNodeProcessStarter(private val spec: OpstackNetworkSpec) : HostNodeProcessStarter
{
    /** What [start] installs: the unit body and where the run script went. */
    internal sealed interface Prepared
    {
        data class Ok(val root: Path, val envId: String, val script: Path, val unitBody: String, val logPath: Path) : Prepared
        data class Failed(val detail: String) : Prepared
    }

    override fun start(
        nodeId: String,
        network: String,
        env: String,
        nodeDir: String,
        launch: NodeLaunchSpec,
    ): HostNodeStartResult
    {
        val prepared = when (val p = prepare(env, nodeDir, launch.args))
        {
            is Prepared.Failed -> return HostNodeStartResult.Failed(p.detail)
            is Prepared.Ok -> p
        }
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = prepared.envId,
            nodeDir = prepared.root,
            primaryBody = prepared.unitBody,
            companions = emptyList(),
            launch = launch.copy(entry = spec.runScript, logFile = spec.relativeLog),
        )
    }

    /** Validates the launch args and writes the run script (0700); everything but the systemd install. */
    internal fun prepare(env: String, nodeDir: String, args: List<String>): Prepared
    {
        val root = Path.of(nodeDir.trim())
        if (!Files.isDirectory(root))
        {
            return Prepared.Failed("node_dir missing: $root")
        }
        val envId = spec.normalizeEnv(env)
        val options = OpstackInstallOptions(
            node = OpstackNodeMode.normalize(argValue(args, "--toolkit-mode=")),
            restore = argValue(args, "--toolkit-restore="),
            snapshotUrl = argValue(args, "--toolkit-snapshot-url="),
        )
        OpstackSnapshotUrl.problem(options.snapshotUrl)?.let { return Prepared.Failed(it) }
        val l1 = when (val parent = ArbL1Parent.resolve(envId, argValue(args, "--toolkit-l1-rpc="), argValue(args, "--toolkit-l1-beacon=")))
        {
            is ArbL1Parent.Result.Missing -> return Prepared.Failed(parent.detail)
            is ArbL1Parent.Result.Ok -> parent.endpoints
        }
        val execution = argPath(args, "--toolkit-execution=") ?: root.resolve("execution").toAbsolutePath().normalize()
        val consensus = argPath(args, "--toolkit-consensus=") ?: root.resolve("consensus").toAbsolutePath().normalize()
        val snapshots = argPath(args, "--toolkit-snapshots=") ?: root.resolve("snapshots").toAbsolutePath().normalize()

        val script = root.resolve(spec.runScript)
        val logPath = root.resolve(spec.relativeLog).toAbsolutePath().normalize()
        val layout = OpstackUnitBodies.Layout(
            nodeDir = root.toAbsolutePath().normalize().toString(),
            execDir = execution.toString(),
            consDir = consensus.toString(),
            snapDir = snapshots.toString(),
            stateDir = root.resolve(".toolkit").toAbsolutePath().normalize().toString(),
            logFile = logPath.toString(),
        )
        try
        {
            Files.createDirectories(execution)
            Files.createDirectories(consensus)
            Files.createDirectories(snapshots)
            Files.createDirectories(script.parent)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            Files.writeString(script, OpstackUnitBodies.runScript(spec, envId, options, layout, l1.rpc, l1.beacon))
            makeOwnerOnlyExecutable(script)
        }
        catch (e: Exception)
        {
            return Prepared.Failed(e.message ?: "write ${spec.network.value} run script failed")
        }
        val body = OpstackUnitBodies.unit(
            spec = spec,
            env = envId,
            nodeDir = layout.nodeDir,
            execStart = script.toAbsolutePath().normalize().toString(),
            logFile = logPath.toString(),
        )
        return Prepared.Ok(root = root, envId = envId, script = script, unitBody = body, logPath = logPath)
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

    private fun makeOwnerOnlyExecutable(path: Path)
    {
        try
        {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE),
            )
        }
        catch (_: Exception)
        {
            path.toFile().setExecutable(true, true)
        }
    }
}
