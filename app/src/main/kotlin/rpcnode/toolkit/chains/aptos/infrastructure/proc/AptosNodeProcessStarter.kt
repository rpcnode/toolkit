package rpcnode.toolkit.chains.aptos.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import rpcnode.toolkit.chains.aptos.infrastructure.AptosBootstrapMode
import rpcnode.toolkit.chains.aptos.infrastructure.AptosClusters
import rpcnode.toolkit.chains.aptos.infrastructure.AptosHostBinaries
import rpcnode.toolkit.chains.aptos.infrastructure.AptosPortTable
import rpcnode.toolkit.chains.aptos.infrastructure.AptosUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport
import rpcnode.toolkit.nodes.infrastructure.host.UnitFlagOverrides

/** Aptos start under [nodeDir]: extract aptos-node → genesis + waypoint → fullnode.yaml → unit. */
class AptosNodeProcessStarter : HostNodeProcessStarter
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
        val envId = AptosClusters.normalizeEnv(env)
        val ports = AptosPortTable.forEnv(envId)
        val mode = argValue(launch.args, "--toolkit-bootstrap=")?.let { AptosBootstrapMode.normalize(it) } ?: AptosBootstrapMode.FAST
        val state = argPath(launch.args, "--toolkit-state=") ?: root.toAbsolutePath().normalize()
        val absRoot = root.toAbsolutePath().normalize()

        val bin = when (val ensured = AptosHostBinaries.ensure(root))
        {
            is AptosHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is AptosHostBinaries.Result.Ok -> ensured.node
        }
        val genesis = ensureConfig(absRoot, AptosUnitBodies.GENESIS_BLOB)
            ?: return HostNodeStartResult.Failed("genesis.blob / waypoint.txt missing under $root — sync Aptos clients (aptos-labs/aptos-networks) first")
        val waypoint = ensureConfig(absRoot, AptosUnitBodies.WAYPOINT)
            ?: return HostNodeStartResult.Failed("genesis.blob / waypoint.txt missing under $root — sync Aptos clients (aptos-labs/aptos-networks) first")
        if (!WAYPOINT.matches(runCatching { Files.readString(waypoint).trim() }.getOrDefault("")))
        {
            return HostNodeStartResult.Failed("waypoint.txt is not a waypoint")
        }
        val modeFile = absRoot.resolve(".toolkit/aptos-bootstrap")
        val previous = runCatching { Files.readString(modeFile).trim() }.getOrNull()
        val db = state.resolve("db")
        if (previous != null && previous != mode && Files.isDirectory(db) && Files.list(db).use { it.findAny().isPresent })
        {
            return HostNodeStartResult.Failed("bootstrap mode changed $previous -> $mode: wipe $state first")
        }
        val yaml = absRoot.resolve(AptosUnitBodies.FULLNODE_YAML)
        try
        {
            Files.createDirectories(state)
            Files.createDirectories(absRoot.resolve("logs"))
            Files.createDirectories(absRoot.resolve(".toolkit"))
            val overrides = UnitFlagOverrides.parse(UnitFlagOverrides.fromArgs(launch.args)).overrides
            Files.writeString(
                yaml,
                AptosUnitBodies.fullnodeYaml(envId, state.toString(), genesis.toString(), waypoint.toString(), ports, mode, overrides),
            )
            Files.writeString(modeFile, "$mode\n")
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write fullnode.yaml failed")
        }
        val body = AptosUnitBodies.unit(
            env = envId,
            nodeDir = absRoot.toString(),
            execStart = AptosUnitBodies.execStart(bin.toAbsolutePath().normalize().toString(), yaml.toString()),
            logFile = absRoot.resolve(AptosUnitBodies.RELATIVE_LOG).toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = launch.copy(
                entry = "bin/${AptosHostBinaries.NODE}",
                logFile = AptosUnitBodies.RELATIVE_LOG,
                // aptos-node takes its settings from fullnode.yaml: extra `--key=value` flags on ExecStart would make it exit
                args = launch.args.filterNot { it.startsWith(UnitFlagOverrides.ARG_PREFIX) },
            ),
        )
    }

    /** `<root>/<name>` or a copy of it from `<root>/conf/`; null when neither holds a non-empty file. */
    private fun ensureConfig(root: Path, name: String): Path?
    {
        val dest = root.resolve(name)
        if (Files.isRegularFile(dest) && Files.size(dest) > 0)
        {
            return dest
        }
        val found = root.resolve("conf").resolve(name).takeIf { Files.isRegularFile(it) && Files.size(it) > 0 } ?: return null
        Files.copy(found, dest, StandardCopyOption.REPLACE_EXISTING)
        return dest
    }

    private fun argValue(args: List<String>, prefix: String): String? =
        args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()?.takeIf { it.isNotEmpty() }

    private fun argPath(args: List<String>, prefix: String): Path?
    {
        val raw = argValue(args, prefix) ?: return null
        if (!raw.startsWith("/") || raw.contains(".."))
        {
            return null
        }
        return Path.of(raw).toAbsolutePath().normalize()
    }

    private companion object
    {
        val WAYPOINT = Regex("^\\d+:[0-9a-f]{64}$")
    }
}
