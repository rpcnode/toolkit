package rpcnode.toolkit.chains.stellar.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.stellar.infrastructure.StellarCaptiveCoreConfig
import rpcnode.toolkit.chains.stellar.infrastructure.StellarClusters
import rpcnode.toolkit.chains.stellar.infrastructure.StellarHistory
import rpcnode.toolkit.chains.stellar.infrastructure.StellarHostBinaries
import rpcnode.toolkit.chains.stellar.infrastructure.StellarPortTable
import rpcnode.toolkit.chains.stellar.infrastructure.StellarUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/** Stellar start under [nodeDir]: unpack the SDF .debs → captive-core.cfg + stellar-rpc.toml + heal script → unit. */
class StellarNodeProcessStarter : HostNodeProcessStarter
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
        val envId = StellarClusters.normalizeEnv(env)
        val cluster = StellarClusters.lookup(envId)
        val ports = StellarPortTable.forEnv(envId)
        val policy = StellarHistory.parse(argValue(launch.args, "--toolkit-history="))
        val blockchain = argPath(launch.args, "--toolkit-blockchain=") ?: root

        when (val ensured = StellarHostBinaries.ensure(root))
        {
            is StellarHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is StellarHostBinaries.Result.Ok -> Unit
        }
        val synced = root.resolve(cluster.captiveCoreConfig)
        if (!Files.isRegularFile(synced))
        {
            return HostNodeStartResult.Failed("${cluster.captiveCoreConfig} missing under $root — sync clients (stellar-rpc) first")
        }
        val logFile = root.resolve(StellarUnitBodies.RELATIVE_LOG)
        try
        {
            for (dir in listOf(blockchain.resolve("captive-core"), root.resolve("logs"), root.resolve(".toolkit"), root.resolve("tmp"), root.resolve("bin")))
            {
                Files.createDirectories(dir)
            }
            Files.writeString(
                root.resolve(StellarUnitBodies.CAPTIVE_CORE_CFG),
                StellarCaptiveCoreConfig.applyPorts(Files.readString(synced), ports.p2p),
            )
            Files.writeString(root.resolve(StellarUnitBodies.RPC_TOML), StellarUnitBodies.rpcToml(cluster, ports, policy, root, blockchain))
            val heal = root.resolve(StellarUnitBodies.HEAL_SCRIPT)
            Files.writeString(
                heal,
                StellarUnitBodies.healScript(logFile.toString(), root.resolve(".toolkit").toString(), blockchain.resolve("captive-core").toString()),
            )
            heal.toFile().setExecutable(true, false)
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write stellar config failed")
        }
        val body = StellarUnitBodies.unit(
            env = envId,
            nodeDir = root.toString(),
            execStart = StellarUnitBodies.execStart(root),
            healScript = root.resolve(StellarUnitBodies.HEAL_SCRIPT).toString(),
            logFile = logFile.toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = launch.copy(
                entry = StellarUnitBodies.RPC_BIN,
                logFile = StellarUnitBodies.RELATIVE_LOG,
                extractArchiveGlob = null,
            ),
        )
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
}
