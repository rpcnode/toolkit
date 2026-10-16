package rpcnode.toolkit.chains.cardano.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoClusters
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoHostBinaries
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoIndexerMode
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoPortTable
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Cardano start under [nodeDir]: unpack the release → generated config.json → unit, plus the optional indexer companions
 * (Ogmios, db-sync + its PostgreSQL preparation) chosen by the install option `indexer`.
 */
class CardanoNodeProcessStarter : HostNodeProcessStarter
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
        val envId = CardanoClusters.normalizeEnv(env)
        val ports = CardanoPortTable.forEnv(envId)
        val blockchain = argPath(launch.args, "--toolkit-blockchain=") ?: root
        if (root.toString().any { it.isWhitespace() } || blockchain.toString().any { it.isWhitespace() })
        {
            return HostNodeStartResult.Failed("node_dir must not contain spaces")
        }
        val mode = CardanoIndexerMode.parse(argValue(launch.args, "--toolkit-indexer="))
        val indexerDir = argPath(launch.args, "--toolkit-indexer-dir=") ?: root.resolve("indexer")
        if (mode.dbSync && indexerDir.toString().any { it.isWhitespace() })
        {
            return HostNodeStartResult.Failed("indexer dir must not contain spaces")
        }
        val bin = when (val ensured = CardanoHostBinaries.ensureNode(root, envId))
        {
            is CardanoHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is CardanoHostBinaries.Result.Ok -> ensured.bins.node
        }
        val db = blockchain.resolve(CardanoUnitBodies.DB_DIR)
        val socket = root.resolve(CardanoUnitBodies.SOCKET)
        if (socket.toString().toByteArray().size > 107)
        {
            return HostNodeStartResult.Failed("socket path too long: $socket")
        }
        val config = root.resolve(CardanoUnitBodies.CONFIG_DIR)
        try
        {
            Files.createDirectories(db)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            Files.createDirectories(config)
            val upstream = Files.readString(config.resolve(CardanoUnitBodies.CONFIG_UPSTREAM))
            Files.writeString(config.resolve(CardanoUnitBodies.CONFIG_JSON), CardanoUnitBodies.configJson(upstream, ports.metrics))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write cardano config failed")
        }
        val execStart = (listOf(bin.toString()) + CardanoUnitBodies.args(root.toString(), db.toString(), socket.toString(), ports.p2p))
            .joinToString(" ")
        val body = CardanoUnitBodies.unit(
            env = envId,
            nodeDir = root.toString(),
            socketPath = socket.toString(),
            execStart = execStart,
            logFile = root.resolve(CardanoUnitBodies.RELATIVE_LOG).toString(),
        )
        val companions = when (val indexer = CardanoIndexerSetup.prepare(root, envId, mode, indexerDir, ports, socket))
        {
            is CardanoIndexerSetup.Result.Failed -> return HostNodeStartResult.Failed(indexer.detail)
            is CardanoIndexerSetup.Result.Ok -> indexer.companions
        }
        // an indexer switched off in the install options must not keep running from its old unit
        retireUnits(root, CardanoIndexerSetup.allUnits(envId) - companions.map { it.first }.toSet())
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = body,
            companions = companions,
            launch = launch.copy(entry = "bin/${CardanoHostBinaries.NODE}", logFile = CardanoUnitBodies.RELATIVE_LOG),
        )
    }

    private fun argValue(args: List<String>, prefix: String): String? =
        args.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)?.trim()?.takeIf { it.isNotEmpty() }

    private fun argPath(args: List<String>, prefix: String): Path?
    {
        val raw = argValue(args, prefix).orEmpty()
        if (raw.isEmpty() || !raw.startsWith("/") || raw.contains(".."))
        {
            return null
        }
        return Path.of(raw).toAbsolutePath().normalize()
    }

    /** Stops, disables and deletes companion units that exist from an earlier Start but are no longer wanted. */
    private fun retireUnits(root: Path, units: Collection<String>)
    {
        for (name in units)
        {
            val file = Path.of("/etc/systemd/system", name)
            if (!Files.exists(file))
            {
                continue
            }
            runCatching { ProcessBuilder("systemctl", "disable", "--now", name).redirectErrorStream(true).start().also { it.inputStream.readBytes() }.waitFor() }
            runCatching { Files.deleteIfExists(file) }
            runCatching { Files.deleteIfExists(root.resolve(".toolkit/systemd.companion.$name.body")) }
        }
    }
}
