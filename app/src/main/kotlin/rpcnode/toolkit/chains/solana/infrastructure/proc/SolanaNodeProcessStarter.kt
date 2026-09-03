package rpcnode.toolkit.chains.solana.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import rpcnode.toolkit.chains.solana.infrastructure.SolanaClusters
import rpcnode.toolkit.chains.solana.infrastructure.SolanaPortTable
import rpcnode.toolkit.chains.solana.infrastructure.SolanaRpcTuning
import rpcnode.toolkit.chains.solana.infrastructure.SolanaSysctlTuning
import rpcnode.toolkit.chains.solana.infrastructure.SolanaUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Agave start entirely under [nodeDir]: ensure binary → identity → fill run-validator.sh
 * from the shipped `run-validator.sh.tmpl` (panel clients dir / agent sync) → unit.
 * Never writes `/opt/solana` or `/etc/solana` (Kotlin host layout; Go sibling differs).
 */
class SolanaNodeProcessStarter : HostNodeProcessStarter
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
        val envId = SolanaClusters.normalizeEnv(env)
        val archive = launch.args.any { it == "--toolkit-archive=1" }
        val ports = SolanaPortTable.forEnv(envId)
        val cluster = SolanaClusters.lookup(envId)
        val tuning = tuningFromArgs(launch.args)
        val sysctl = sysctlFromArgs(launch.args)

        val ledger = argValue(launch.args, "--toolkit-ledger=")
            ?: root.toAbsolutePath().normalize().toString()
        val accounts = argValue(launch.args, "--toolkit-accounts=")
            ?: root.resolve("accounts").toAbsolutePath().normalize().toString()
        val snapshots = argValue(launch.args, "--toolkit-snapshots=")
            ?: root.resolve("snapshots").toAbsolutePath().normalize().toString()

        val bin = when (val ensured = SolanaAgaveBins.ensureValidator(root))
        {
            is SolanaAgaveBins.EnsureResult.Ok -> ensured.path
            is SolanaAgaveBins.EnsureResult.Pending ->
                return HostNodeStartResult.Pending(ensured.detail)
            is SolanaAgaveBins.EnsureResult.Failed ->
                return HostNodeStartResult.Failed(ensured.detail)
        }
        val keygen = SolanaAgaveBins.ensureKeygen(root)
        val scriptPath = root.resolve(SolanaUnitBodies.RUN_VALIDATOR_NAME)
        val identity = root.resolve(".toolkit/validator-keypair.json")
        // Stable relative path for panel Sync / clients.yml requirements.logFile.
        val relativeLog = "logs/validator.log"
        val logPath = root.resolve(relativeLog)

        try
        {
            Files.createDirectories(Path.of(ledger))
            Files.createDirectories(Path.of(accounts))
            Files.createDirectories(Path.of(snapshots))
            Files.createDirectories(SolanaAgaveBins.binDir(root))
            Files.createDirectories(root.resolve(".toolkit"))
            Files.createDirectories(root.resolve("logs"))
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "mkdir solana dirs failed")
        }

        when (val id = ensureIdentity(identity, keygen))
        {
            is IdentityResult.Failed -> return HostNodeStartResult.Failed(id.detail)
            IdentityResult.Ok -> Unit
        }

        when (val tuned = SolanaHostTune.ensureSysctl(sysctl))
        {
            is SolanaHostTune.Result.Failed ->
                return HostNodeStartResult.Failed(
                    "Agave needs OS network tuning before Start: ${tuned.detail}. " +
                        "See https://docs.anza.xyz/operations/guides/validator-start#system-tuning",
                )
            SolanaHostTune.Result.Ok -> Unit
        }

        SolanaPortTable.requireUdpRange(ports.p2p, cluster.p2pRangeSpan)?.let { detail ->
            return HostNodeStartResult.Failed(detail)
        }

        val p2pRange = SolanaClusters.p2pRange(ports.p2p, cluster.p2pRangeSpan)
        val script = SolanaUnitBodies.runValidatorScript(
            bin = bin.toAbsolutePath().normalize().toString(),
            identity = identity.toAbsolutePath().normalize().toString(),
            ledger = ledger,
            accounts = accounts,
            snapshots = snapshots,
            logPath = logPath.toAbsolutePath().normalize().toString(),
            rpcPort = ports.http,
            p2pRange = p2pRange,
            cluster = cluster,
            archive = archive,
            // Safer default for NAT/VPN hosts (Go DetectEgress often false).
            egressReachable = false,
            tuning = tuning,
            template = SolanaUnitBodies.loadRunValidatorTemplate(root),
        )
        try
        {
            Files.writeString(scriptPath, script)
            makeExecutable(scriptPath)
        }
        catch (e: Exception)
        {
            return HostNodeStartResult.Failed(e.message ?: "write run-validator.sh failed")
        }

        val body = SolanaUnitBodies.unit(
            envId,
            scriptPath.toAbsolutePath().normalize().toString(),
            nofile = tuning.limitNofile,
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = envId,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = launch.copy(entry = SolanaAgaveBins.VALIDATOR, logFile = relativeLog),
        )
    }

    private fun tuningFromArgs(args: List<String>): SolanaRpcTuning
    {
        fun intArg(prefix: String, default: Int): Int =
            argValue(args, prefix)?.toIntOrNull()?.takeIf { it > 0 } ?: default
        return SolanaRpcTuning(
            rpcThreads = intArg("--toolkit-rpc-threads=", SolanaUnitBodies.RPC_THREADS),
            rpcPubsubWorkerThreads = intArg(
                "--toolkit-rpc-pubsub-worker-threads=",
                SolanaUnitBodies.RPC_PUBSUB_WORKER_THREADS,
            ),
            rpcPubsubMaxActiveSubscriptions = intArg(
                "--toolkit-rpc-pubsub-max-active-subscriptions=",
                SolanaUnitBodies.RPC_PUBSUB_MAX_ACTIVE_SUBSCRIPTIONS,
            ),
            rpcMaxRequestBodySize = intArg(
                "--toolkit-rpc-max-request-body-size=",
                SolanaUnitBodies.RPC_MAX_REQUEST_BODY_SIZE,
            ),
            limitNofile = intArg("--toolkit-limit-nofile=", SolanaUnitBodies.NODE_NOFILE),
        )
    }

    private fun sysctlFromArgs(args: List<String>): SolanaSysctlTuning
    {
        fun longArg(prefix: String, default: Long): Long =
            argValue(args, prefix)?.toLongOrNull()?.takeIf { it > 0 } ?: default
        return SolanaSysctlTuning(
            rmemDefault = longArg("--toolkit-sysctl-rmem-default=", SolanaSysctlTuning.RECOMMENDED_RMEM),
            rmemMax = longArg("--toolkit-sysctl-rmem-max=", SolanaSysctlTuning.RECOMMENDED_RMEM),
            wmemDefault = longArg("--toolkit-sysctl-wmem-default=", SolanaSysctlTuning.RECOMMENDED_WMEM),
            wmemMax = longArg("--toolkit-sysctl-wmem-max=", SolanaSysctlTuning.RECOMMENDED_WMEM),
            vmMaxMapCount = longArg(
                "--toolkit-sysctl-vm-max-map-count=",
                SolanaSysctlTuning.RECOMMENDED_VM_MAX_MAP_COUNT,
            ),
            fsNrOpen = longArg("--toolkit-sysctl-fs-nr-open=", SolanaSysctlTuning.RECOMMENDED_FS_NR_OPEN),
        )
    }

    private fun argValue(args: List<String>, prefix: String): String?
    {
        val raw = args.firstOrNull { it.startsWith(prefix) } ?: return null
        return raw.removePrefix(prefix).trim().takeIf { it.isNotEmpty() }
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

    private sealed interface IdentityResult
    {
        data object Ok : IdentityResult
        data class Failed(val detail: String) : IdentityResult
    }

    private fun ensureIdentity(path: Path, keygen: Path?): IdentityResult
    {
        if (Files.isRegularFile(path) && Files.size(path) > 0)
        {
            return IdentityResult.Ok
        }
        if (keygen == null || !Files.isRegularFile(keygen))
        {
            return IdentityResult.Failed("solana-keygen missing; cannot create $path")
        }
        return try
        {
            Files.createDirectories(path.parent)
            val pb = ProcessBuilder(
                keygen.toAbsolutePath().toString(),
                "new",
                "--no-passphrase",
                "--outfile",
                path.toAbsolutePath().toString(),
                "--force",
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code != 0 || !Files.isRegularFile(path))
            {
                return IdentityResult.Failed("solana-keygen failed: $out")
            }
            IdentityResult.Ok
        }
        catch (e: Exception)
        {
            IdentityResult.Failed(e.message ?: "identity create failed")
        }
    }
}
