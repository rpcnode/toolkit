package rpcnode.toolkit.chains.ton.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import rpcnode.toolkit.chains.ton.infrastructure.TonClusters
import rpcnode.toolkit.chains.ton.infrastructure.TonInstallOptions
import rpcnode.toolkit.chains.ton.infrastructure.TonPorts
import rpcnode.toolkit.chains.ton.infrastructure.TonUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * TON start: scripts under [nodeDir], MyTonCtrl bootstrap to host paths, `/var/ton-work` → blockchain.
 */
class TonNodeProcessStarter : HostNodeProcessStarter
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
        val envId = TonClusters.normalizeEnv(env)
        val ports = TonPorts.forEnv(envId)
        val history = argValue(launch.args, "--toolkit-history=")
            ?.let { TonInstallOptions.normalize(it) }
            ?: TonInstallOptions.DUMP
        val blockchain = argPath(launch.args, "--toolkit-blockchain=")
            ?: root.toAbsolutePath().normalize()
        val archive = argPath(launch.args, "--toolkit-archive=")
            ?: root.resolve("archive").toAbsolutePath().normalize()

        val toolkit = root.resolve(".toolkit")
        val bin = root.resolve("bin")
        val logs = root.resolve("logs")
        try
        {
            Files.createDirectories(blockchain)
            Files.createDirectories(archive)
            Files.createDirectories(toolkit)
            Files.createDirectories(bin)
            Files.createDirectories(logs)
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir failed")
        }

        when (val link = ensureTonWork(blockchain))
        {
            is LinkResult.Failed -> return HostNodeStartResult.Failed(link.detail)
            LinkResult.Ok -> Unit
        }

        val bootScript = root.resolve(TonUnitBodies.BOOTSTRAP_SCRIPT)
        val startScript = root.resolve(TonUnitBodies.NODE_START_SCRIPT)
        val markerPath = root.resolve(TonUnitBodies.BOOTSTRAP_DONE).toAbsolutePath().normalize().toString()
        val bootUnitName = TonUnitBodies.bootstrapUnitName(envId)
        try
        {
            Files.writeString(
                bootScript,
                TonUnitBodies.bootstrapScript(
                    env = envId,
                    data = blockchain.toAbsolutePath().normalize().toString(),
                    markerDir = toolkit.toAbsolutePath().normalize().toString(),
                    logDir = logs.toAbsolutePath().normalize().toString(),
                    thaPort = ports.http,
                    p2pPort = ports.p2p,
                    history = history,
                ),
            )
            val logAbs = logs.resolve("ton.log").toAbsolutePath().normalize().toString()
            Files.writeString(
                startScript,
                TonUnitBodies.nodeStartScript(
                    markerPath = markerPath,
                    bootstrapUnit = bootUnitName,
                    logFile = logAbs,
                ),
            )
            makeExecutable(bootScript)
            makeExecutable(startScript)
            Files.writeString(
                toolkit.resolve("rpcnode-ton.json"),
                """
                {
                  "network": "ton",
                  "env": "$envId",
                  "chain": "${TonClusters.chainFlag(envId)}",
                  "mode": "liteserver",
                  "history": "$history",
                  "tha_port": ${ports.http},
                  "validator_port": ${ports.p2p},
                  "data_dir": "${blockchain.toAbsolutePath().normalize()}",
                  "archive_ttl": ${TonUnitBodies.ARCHIVE_TTL_SEC},
                  "state_ttl": ${TonUnitBodies.STATE_TTL_SEC}
                }
                """.trimIndent() + "\n",
            )
            // Bootstrap unit is written/enabled here but NOT restarted by installCustomUnits —
            // MyTonCtrl install can take hours; node-start.sh starts it with --no-block.
            val bootBody = TonUnitBodies.bootstrapUnit(
                env = envId,
                execStart = bootScript.toAbsolutePath().normalize().toString(),
            )
            Files.writeString(Path.of("/etc/systemd/system", bootUnitName), bootBody)
            Files.writeString(toolkit.resolve("systemd.companion.$bootUnitName.body"), bootBody)
            val enableBoot = ProcessBuilder("systemctl", "enable", bootUnitName)
                .redirectErrorStream(true)
                .start()
            enableBoot.waitFor()
            ProcessBuilder("systemctl", "daemon-reload")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write ton scripts failed")
        }

        val nodeDirAbs = root.toAbsolutePath().normalize().toString()
        val logAbs = logs.resolve("ton.log").toAbsolutePath().normalize().toString()
        val nodeBody = TonUnitBodies.nodeUnit(
            env = envId,
            nodeDir = nodeDirAbs,
            execStart = startScript.toAbsolutePath().normalize().toString(),
            logFile = logAbs,
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = nodeBody,
            companions = emptyList(),
            launch = launch.copy(
                entry = TonUnitBodies.NODE_START_SCRIPT,
                logFile = TonUnitBodies.RELATIVE_LOG,
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
            // Non-POSIX FS — shebang + bash via systemd still works if mode allows.
        }
    }

    private sealed interface LinkResult
    {
        data object Ok : LinkResult
        data class Failed(val detail: String) : LinkResult
    }

    private fun ensureTonWork(data: Path): LinkResult
    {
        val work = Path.of("/var/ton-work")
        val dataAbs = data.toAbsolutePath().normalize()
        try
        {
            Files.createDirectories(dataAbs)
            if (Files.isSymbolicLink(work))
            {
                val target = runCatching { Files.readSymbolicLink(work) }.getOrNull()
                if (target != null && target.toAbsolutePath().normalize() == dataAbs)
                {
                    return LinkResult.Ok
                }
                if (target == null || !Files.exists(target))
                {
                    Files.deleteIfExists(work)
                    Files.createSymbolicLink(work, dataAbs)
                    return LinkResult.Ok
                }
                return LinkResult.Failed(
                    "/var/ton-work already points to $target (need $dataAbs) — one TON env per host",
                )
            }
            if (!Files.exists(work))
            {
                Files.createSymbolicLink(work, dataAbs)
                return LinkResult.Ok
            }
            if (Files.isDirectory(work))
            {
                val live = Files.isRegularFile(work.resolve("keys/client")) ||
                    Files.isRegularFile(work.resolve("db/config.json"))
                if (live)
                {
                    return LinkResult.Failed(
                        "/var/ton-work is a live directory — refuse to replace (one TON env per host)",
                    )
                }
                // Empty leftover dir — replace with symlink (same as Go bootstrap).
                work.toFile().deleteRecursively()
                Files.createSymbolicLink(work, dataAbs)
                return LinkResult.Ok
            }
            return LinkResult.Failed("/var/ton-work exists and is not a symlink")
        }
        catch (e: Exception)
        {
            return LinkResult.Failed(e.message ?: "ensure /var/ton-work failed")
        }
    }
}
