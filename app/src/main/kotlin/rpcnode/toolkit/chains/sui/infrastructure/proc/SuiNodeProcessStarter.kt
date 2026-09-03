package rpcnode.toolkit.chains.sui.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import rpcnode.toolkit.chains.sui.infrastructure.SuiClusters
import rpcnode.toolkit.chains.sui.infrastructure.SuiHostBinaries
import rpcnode.toolkit.chains.sui.infrastructure.SuiPortTable
import rpcnode.toolkit.chains.sui.infrastructure.SuiUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Sui start entirely under [nodeDir]: extract bins → genesis → fullnode.yaml → unit.
 * Never writes `/opt/sui` or `/etc/sui`.
 */
class SuiNodeProcessStarter : HostNodeProcessStarter
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
        val envId = SuiClusters.normalizeEnv(env)
        val cluster = SuiClusters.lookup(envId)
        val ports = SuiPortTable.forEnv(envId)

        val bins = when (val ensured = SuiHostBinaries.ensure(root))
        {
            is SuiHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is SuiHostBinaries.Result.Ok -> ensured.bins
        }

        val state = argPath(launch.args, "--toolkit-state=")
            ?: root.toAbsolutePath().normalize()
        val index = argPath(launch.args, "--toolkit-index=")
            ?: root.resolve("index").toAbsolutePath().normalize()

        val genesis = root.resolve(SuiUnitBodies.GENESIS_BLOB)
        when (val g = ensureGenesis(root, genesis))
        {
            is GenesisResult.Failed -> return HostNodeStartResult.Failed(g.detail)
            GenesisResult.Ok -> Unit
        }

        val yamlPath = root.resolve(SuiUnitBodies.FULLNODE_YAML)
        val relativeLog = SuiUnitBodies.RELATIVE_LOG
        val logPath = root.resolve(relativeLog)
        try
        {
            Files.createDirectories(state)
            Files.createDirectories(index)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            Files.writeString(
                yamlPath,
                SuiUnitBodies.fullnodeYaml(
                    env = envId,
                    dbPath = state.toString(),
                    metricsPort = ports.metrics,
                    rpcPort = ports.http,
                    p2pPort = ports.p2p,
                    genesisPath = genesis.toAbsolutePath().normalize().toString(),
                    archiveUrl = cluster.archiveUrl,
                ),
            )
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write fullnode.yaml failed")
        }

        val execStart = "${bins.node.toAbsolutePath().normalize()} --config-path ${yamlPath.toAbsolutePath().normalize()}"
        val body = SuiUnitBodies.unit(
            env = envId,
            nodeDir = root.toAbsolutePath().normalize().toString(),
            execStart = execStart,
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
                entry = "bin/${SuiHostBinaries.NODE}",
                logFile = relativeLog,
            ),
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

    private sealed interface GenesisResult
    {
        data object Ok : GenesisResult
        data class Failed(val detail: String) : GenesisResult
    }

    private fun ensureGenesis(nodeDir: Path, dest: Path): GenesisResult
    {
        if (Files.isRegularFile(dest) && Files.size(dest) > 0)
        {
            return GenesisResult.Ok
        }
        val candidates = listOf(
            nodeDir.resolve(SuiUnitBodies.GENESIS_BLOB),
            nodeDir.resolve("conf").resolve(SuiUnitBodies.GENESIS_BLOB),
        )
        val found = candidates.firstOrNull { Files.isRegularFile(it) && Files.size(it) > 0 }
        if (found != null && found != dest)
        {
            return try
            {
                Files.copy(found, dest, StandardCopyOption.REPLACE_EXISTING)
                GenesisResult.Ok
            }
            catch (e: Exception)
            {
                GenesisResult.Failed(e.message ?: "copy genesis.blob failed")
            }
        }
        if (Files.isRegularFile(dest) && Files.size(dest) > 0)
        {
            return GenesisResult.Ok
        }
        return GenesisResult.Failed(
            "genesis.blob missing under $nodeDir — sync Sui clients (MystenLabs/sui-genesis) first",
        )
    }
}
