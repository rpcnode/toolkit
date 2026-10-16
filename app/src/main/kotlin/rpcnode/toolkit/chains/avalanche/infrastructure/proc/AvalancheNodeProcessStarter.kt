package rpcnode.toolkit.chains.avalanche.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalancheClusters
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalancheHostBinaries
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalancheNodeMode
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalanchePortTable
import rpcnode.toolkit.chains.avalanche.infrastructure.AvalancheUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Avalanche start under [nodeDir]: extract avalanchego → C-Chain config.json → unit.
 * The chain DB goes to the blockchain disk role; staking keys, configs and logs stay under the node dir.
 */
class AvalancheNodeProcessStarter : HostNodeProcessStarter
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
        val envId = AvalancheClusters.normalizeEnv(env)
        val ports = AvalanchePortTable.forEnv(envId)
        val mode = argValue(launch.args, "--toolkit-mode=")?.let { AvalancheNodeMode.normalize(it) } ?: AvalancheNodeMode.FULL
        val blockchain = argPath(launch.args, "--toolkit-blockchain=") ?: root.toAbsolutePath().normalize()

        val dataDir = root.resolve(AvalancheUnitBodies.DATA_DIR).toAbsolutePath().normalize()
        val bin = when (val ensured = AvalancheHostBinaries.ensure(root, dataDir.resolve("plugins")))
        {
            is AvalancheHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is AvalancheHostBinaries.Result.Ok -> ensured.node
        }

        val dbDir = blockchain.resolve("db")
        val logDir = root.resolve("logs").resolve("avalanchego").toAbsolutePath().normalize()
        val logPath = root.resolve(AvalancheUnitBodies.RELATIVE_LOG)
        val chainConfig = dataDir.resolve("configs").resolve("chains").resolve("C").resolve("config.json")
        try
        {
            Files.createDirectories(dbDir)
            Files.createDirectories(logDir)
            Files.createDirectories(chainConfig.parent)
            Files.createDirectories(root.resolve(".toolkit"))
            Files.writeString(chainConfig, AvalancheUnitBodies.cChainConfig(mode))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write C-Chain config failed")
        }

        val args = AvalancheUnitBodies.args(
            env = envId,
            dataDir = dataDir.toString(),
            dbDir = dbDir.toAbsolutePath().normalize().toString(),
            logDir = logDir.toString(),
            httpPort = ports.http,
            stakingPort = ports.staking,
        )
        val execStart = (listOf(bin.toAbsolutePath().normalize().toString()) + args).joinToString(" ")
        val body = AvalancheUnitBodies.unit(
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
                entry = "bin/${AvalancheHostBinaries.NODE}",
                logFile = AvalancheUnitBodies.RELATIVE_LOG,
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
}
