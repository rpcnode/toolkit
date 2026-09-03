package rpcnode.toolkit.chains.base.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.chains.base.infrastructure.BaseClusters
import rpcnode.toolkit.chains.base.infrastructure.BaseHostBinaries
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Runs official Base V2 snapshot bootstrap via the shipped heal script
 * (`resources/chains/base/snapshot-heal.sh.tmpl`). Progress is mirrored from
 * `<datadir>/.snapshot-state.json`.
 */
class BaseOfficialSnapshotRunner
{
    private val log = LoggerFactory.getLogger(BaseOfficialSnapshotRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Paths(
        val data: Path,
        val opt: Path,
        val marker: Path,
        val state: Path,
        val logFile: Path,
        val script: Path,
    )

    fun layout(dataDir: Path, env: String): Paths
    {
        val data = dataDir.toAbsolutePath().normalize()
        val envId = BaseClusters.lookup(env).env
        val opt = Path.of("/opt/base", envId)
        return Paths(
            data = data,
            opt = opt,
            marker = data.resolve(".snapshot-ready"),
            state = data.resolve(".snapshot-state.json"),
            logFile = Path.of("/var/log/base", "$envId-snapshot.log"),
            script = opt.resolve("bin").resolve("base-official-snapshot.sh"),
        )
    }

    fun writeHealScript(
        paths: Paths,
        env: String,
        flavor: String,
        rethBin: Path,
        manifestUrl: String? = null,
    ): Path
    {
        val cluster = BaseClusters.lookup(env)
        val need = BaseClusters.snapshotRequiredGiB(cluster.env, flavor)
        val body = HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("base", "snapshot-heal.sh.tmpl"),
            mapOf(
                "env" to escapeSingleQuotes(cluster.env),
                "bin" to escapeSingleQuotes(rethBin.toAbsolutePath().toString()),
                "reth_dir" to escapeSingleQuotes(paths.data.toString()),
                "marker" to escapeSingleQuotes(paths.marker.toString()),
                "state" to escapeSingleQuotes(paths.state.toString()),
                "log" to escapeSingleQuotes(paths.logFile.toString()),
                "flavor" to escapeSingleQuotes(flavor),
                "chain" to escapeSingleQuotes(cluster.rethChain),
                "need_gib" to need.toString(),
                "manifest_url" to escapeSingleQuotes(manifestUrl?.trim().orEmpty()),
            ),
        )
        Files.createDirectories(paths.script.parent)
        Files.createDirectories(paths.logFile.parent)
        Files.createDirectories(paths.data)
        Files.writeString(paths.script, body)
        try
        {
            Files.setPosixFilePermissions(
                paths.script,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
        }
        catch (_: Exception)
        {
            paths.script.toFile().setExecutable(true)
        }
        return paths.script
    }

    fun ensureRethBin(env: String, dataDir: Path): Path?
    {
        return BaseHostBinaries.resolveReth(env, dataDir)
    }

    fun startProcess(script: Path): Process
    {
        val pb = ProcessBuilder("bash", script.toAbsolutePath().toString())
        pb.redirectErrorStream(true)
        return pb.start()
    }

    fun readState(statePath: Path): StateSnapshot?
    {
        if (!Files.isRegularFile(statePath))
        {
            return null
        }
        return try
        {
            val root = json.parseToJsonElement(Files.readString(statePath)).jsonObject
            StateSnapshot(
                phase = root["phase"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "download" },
                pct = root["pct"]?.jsonPrimitive?.doubleOrNull,
                detail = root["detail"]?.jsonPrimitive?.content?.trim().orEmpty(),
                error = root["error"]?.jsonPrimitive?.content?.trim().orEmpty(),
            )
        }
        catch (e: Exception)
        {
            log.debug("base snapshot state parse: {}", e.message)
            null
        }
    }

    /**
     * Prefer live progress from the heal log (reth indicatif lines). Fall back to
     * `.snapshot-state.json` which the script only updates at phase boundaries.
     */
    fun readLiveProgress(paths: Paths, flavor: String): StateSnapshot?
    {
        val fromLog = readLogProgress(paths.logFile, flavor)
        val fromState = readState(paths.state)
        if (fromLog == null)
        {
            return fromState
        }
        if (fromState?.phase.equals("error", ignoreCase = true) == true)
        {
            return fromState
        }
        return StateSnapshot(
            phase = fromLog.phase.ifBlank { fromState?.phase ?: "download" },
            pct = fromLog.pct ?: fromState?.pct,
            detail = fromLog.detail.ifBlank { fromState?.detail.orEmpty() },
            error = fromState?.error.orEmpty(),
        )
    }

    fun readLogProgress(logFile: Path, flavor: String): StateSnapshot?
    {
        if (!Files.isRegularFile(logFile))
        {
            return null
        }
        return try
        {
            val text = Files.readString(logFile)
            val p = BaseSnapshotLogProgress.parse(text, flavor) ?: return null
            StateSnapshot(
                phase = p.phase.ifBlank { "download" },
                pct = p.pct,
                detail = p.detail,
                error = "",
            )
        }
        catch (e: Exception)
        {
            log.debug("base snapshot log progress: {}", e.message)
            null
        }
    }

    fun markerReady(marker: Path): Boolean = Files.isRegularFile(marker)

    fun rethV2Present(rethDir: Path): Boolean
    {
        val mdbx = rethDir.resolve("db").resolve("mdbx.dat")
        if (!Files.isRegularFile(mdbx) || Files.size(mdbx) <= 0)
        {
            return false
        }
        return dirNonEmpty(rethDir.resolve("static_files")) || dirNonEmpty(rethDir.resolve("rocksdb"))
    }

    private fun dirNonEmpty(path: Path): Boolean
    {
        if (!Files.isDirectory(path))
        {
            return false
        }
        Files.list(path).use { return it.findFirst().isPresent }
    }

    data class StateSnapshot(
        val phase: String,
        val pct: Double?,
        val detail: String,
        val error: String,
    )

    private fun escapeSingleQuotes(raw: String): String =
        raw.trim().replace("'", "'\\''")
}
