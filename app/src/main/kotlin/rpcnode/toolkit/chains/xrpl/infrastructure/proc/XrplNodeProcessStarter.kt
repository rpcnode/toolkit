package rpcnode.toolkit.chains.xrpl.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplClusters
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplHistory
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplHostBinaries
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplPortTable
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplUnitBodies
import rpcnode.toolkit.chains.xrpl.infrastructure.XrplValidators
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * XRPL start entirely under [nodeDir]: extract deb → validators → cfg → unit.
 * Never writes `/opt/ripple` or `/etc/opt/ripple`.
 */
class XrplNodeProcessStarter : HostNodeProcessStarter
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
        val envId = XrplClusters.normalizeEnv(env)
        val cluster = XrplClusters.lookup(envId)
        val ports = XrplPortTable.forEnv(envId)
        val historyMode = launch.args.firstOrNull { it.startsWith("--toolkit-history=") }
            ?.removePrefix("--toolkit-history=")
            ?: XrplHistory.WEEKS
        val policy = XrplHistory.parse(historyMode)

        val bins = when (val ensured = XrplHostBinaries.ensure(root))
        {
            is XrplHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is XrplHostBinaries.Result.Ok -> ensured.bins
        }

        val data = argPath(launch.args, "--toolkit-ledger=")
            ?: root.toAbsolutePath().normalize()
        val relativeLog = XrplUnitBodies.RELATIVE_LOG
        val logPath = root.resolve(relativeLog)
        val cfgPath = root.resolve(XrplUnitBodies.CFG_NAME)
        val validatorsPath = root.resolve(XrplUnitBodies.VALIDATORS_NAME)
        val historyPath = root.resolve(XrplUnitBodies.HISTORY_JSON)
        val nudb = data.resolve("db").resolve("nudb")
        val hasLedger = Files.isDirectory(nudb) &&
            runCatching {
                Files.list(nudb).use { it.findFirst().isPresent }
            }.getOrDefault(false)

        try
        {
            Files.createDirectories(data.resolve("db").resolve("nudb"))
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            Files.writeString(validatorsPath, XrplValidators.body(envId))
            Files.writeString(
                historyPath,
                """{"mode":"${policy.mode}","ledgers":${policy.ledgers}}""" + "\n",
            )
            Files.writeString(
                cfgPath,
                XrplUnitBodies.cfg(
                    cluster = cluster,
                    etc = root,
                    data = data,
                    ports = ports,
                    policy = policy,
                    hasLedger = hasLedger,
                ),
            )
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write xrpld.cfg failed")
        }

        val binAbs = bins.node.toAbsolutePath().normalize().toString()
        val confAbs = cfgPath.toAbsolutePath().normalize().toString()
        val body = XrplUnitBodies.unit(
            env = envId,
            nodeDir = root.toAbsolutePath().normalize().toString(),
            bin = binAbs,
            conf = confAbs,
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
                entry = "bin/${XrplUnitBodies.BIN_NAME}",
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
}
