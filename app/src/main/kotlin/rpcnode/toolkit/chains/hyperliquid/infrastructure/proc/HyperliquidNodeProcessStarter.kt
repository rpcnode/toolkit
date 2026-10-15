package rpcnode.toolkit.chains.hyperliquid.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.hyperliquid.infrastructure.HyperliquidClusters
import rpcnode.toolkit.chains.hyperliquid.infrastructure.HyperliquidGossipPeers
import rpcnode.toolkit.chains.hyperliquid.infrastructure.HyperliquidHostBinaries
import rpcnode.toolkit.chains.hyperliquid.infrastructure.HyperliquidUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Hyperliquid start entirely under [nodeDir]:
 * bin/hl-visor → hl/ workdir + gossip configs → unit with HOME=node_dir.
 * Never writes `/opt/hyperliquid` or `/etc/hyperliquid`.
 */
class HyperliquidNodeProcessStarter : HostNodeProcessStarter
{
    private val log = org.slf4j.LoggerFactory.getLogger(HyperliquidNodeProcessStarter::class.java)

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
        val envId = HyperliquidClusters.normalizeEnv(env)
        val cluster = HyperliquidClusters.lookup(envId)

        val bins = when (val ensured = HyperliquidHostBinaries.ensure(root))
        {
            is HyperliquidHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(ensured.detail)
            is HyperliquidHostBinaries.Result.Ok -> ensured.bins
        }

        val dataDir = argPath(launch.args, "--toolkit-chain=")
            ?: root.toAbsolutePath().normalize()
        val hlDir = root.resolve(HyperliquidUnitBodies.HL_SUBDIR)
        val dataLink = hlDir.resolve(HyperliquidUnitBodies.DATA_LINK)
        val relativeLog = HyperliquidUnitBodies.RELATIVE_LOG
        val logPath = root.resolve(relativeLog)

        val peers = HyperliquidGossipPeers.resolve(cluster)
        if (peers.isEmpty())
        {
            return HostNodeStartResult.Failed(
                "hyperliquid/$envId: root_node_ips empty — hl-node panics (gossip_config); check seed peers",
            )
        }

        try
        {
            Files.createDirectories(dataDir)
            Files.createDirectories(hlDir.resolve("tmp"))
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            ensureSymlink(dataLink, dataDir)
            importGpgBestEffort(root)

            val visorBody = HyperliquidUnitBodies.visorJson(cluster)
            val gossipBody = HyperliquidUnitBodies.gossipJson(cluster, peers)
            // Workdir (hl/), HOME (node_dir) and the binary's own dir (bin/): hl-visor reads visor.json next to
            // its executable and panics ("missing file: …/bin/visor.json") when it is absent there.
            for (dir in listOf(hlDir, root, HyperliquidHostBinaries.binDir(root)))
            {
                Files.writeString(dir.resolve(HyperliquidUnitBodies.VISOR_JSON), visorBody)
                Files.writeString(dir.resolve(HyperliquidUnitBodies.GOSSIP_JSON), gossipBody)
            }
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "hyperliquid layout failed")
        }

        val nodeAbs = root.toAbsolutePath().normalize().toString()
        val workAbs = hlDir.toAbsolutePath().normalize().toString()
        val execStart = HyperliquidUnitBodies.execStart(
            bins.visor.toAbsolutePath().normalize().toString(),
        )
        val body = HyperliquidUnitBodies.unit(
            env = envId,
            nodeDir = nodeAbs,
            workdir = workAbs,
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
                entry = "bin/${HyperliquidHostBinaries.BINARY}",
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

    private fun ensureSymlink(link: Path, target: Path)
    {
        val absTarget = target.toAbsolutePath().normalize()
        if (Files.isSymbolicLink(link) || Files.exists(link))
        {
            Files.deleteIfExists(link)
        }
        Files.createSymbolicLink(link, absTarget)
    }

    /**
     * hl-visor verifies every hl-node it downloads with `gpg` from the unit's HOME (= node_dir). Importing into the
     * agent's own keyring (/root/.gnupg) leaves node_dir/.gnupg empty: verification fails and hl-visor exits in a
     * restart loop. Import into node_dir/.gnupg, as the unit will see it.
     */
    private fun importGpgBestEffort(nodeDir: Path)
    {
        val key = nodeDir.resolve("pub_key.asc")
        if (!Files.isRegularFile(key) || Files.size(key) <= 0)
        {
            return
        }
        val home = nodeDir.toAbsolutePath().normalize()
        val gnupg = home.resolve(".gnupg")
        runCatching {
            Files.createDirectories(gnupg)
            runCatching {
                Files.setPosixFilePermissions(gnupg, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
            }
            val pb = ProcessBuilder("gpg", "--batch", "--homedir", gnupg.toString(), "--import", key.toAbsolutePath().toString())
                .redirectErrorStream(true)
            pb.environment()["HOME"] = home.toString()
            pb.environment()["GNUPGHOME"] = gnupg.toString()
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0)
            {
                log.warn("hyperliquid gpg --import into {} failed: {}", gnupg, out.trim().take(400))
            }
        }.onFailure { log.warn("hyperliquid gpg --import into {}: {} (is gnupg installed?)", gnupg, it.message) }
    }
}
