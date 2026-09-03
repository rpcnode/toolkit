package rpcnode.toolkit.chains.sui.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.chains.sui.infrastructure.SuiClusters
import rpcnode.toolkit.chains.sui.infrastructure.SuiHostBinaries
import rpcnode.toolkit.chains.sui.infrastructure.SuiUnitBodies
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Runs Mysten formal R2 snapshot bootstrap via the shipped heal script
 * (`resources/chains/sui/formal-snapshot.sh.tmpl`). Progress from
 * `<db>/.snapshot-state.json` / log.
 */
class SuiFormalSnapshotRunner
{
    private val log = LoggerFactory.getLogger(SuiFormalSnapshotRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Paths(
        val db: Path,
        val nodeDir: Path,
        val marker: Path,
        val state: Path,
        val logFile: Path,
        val script: Path,
        val genesis: Path,
    )

    fun layout(dbDir: Path, nodeDir: Path? = null): Paths
    {
        val db = dbDir.toAbsolutePath().normalize()
        // Prefer the dest leaf itself (client sync + Start usually land here);
        // parent is a fallback when db is a nested leaf under a network root.
        val root = (nodeDir ?: db).toAbsolutePath().normalize()
        val genesisCandidates = listOf(
            root.resolve(SuiUnitBodies.GENESIS_BLOB),
            db.resolve(SuiUnitBodies.GENESIS_BLOB),
            root.parent?.resolve(SuiUnitBodies.GENESIS_BLOB),
        )
        val genesis = genesisCandidates
            .filterNotNull()
            .firstOrNull { Files.isRegularFile(it) && Files.size(it) > 0 }
            ?: root.resolve(SuiUnitBodies.GENESIS_BLOB)
        return Paths(
            db = db,
            nodeDir = root,
            marker = db.resolve(".snapshot-ready"),
            state = db.resolve(".snapshot-state.json"),
            logFile = root.resolve("logs").resolve("sui-snapshot.log"),
            script = root.resolve(".toolkit").resolve("sui-formal-snapshot.sh"),
            genesis = genesis,
        )
    }

    fun writeHealScript(
        paths: Paths,
        env: String,
        toolBin: Path,
    ): Path
    {
        val cluster = SuiClusters.lookup(env)
        val body = HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("sui", "formal-snapshot.sh.tmpl"),
            mapOf(
                "env" to escapeSingleQuotes(cluster.env),
                "tool" to escapeSingleQuotes(toolBin.toAbsolutePath().toString()),
                "genesis" to escapeSingleQuotes(paths.genesis.toAbsolutePath().toString()),
                "db" to escapeSingleQuotes(paths.db.toString()),
                "marker" to escapeSingleQuotes(paths.marker.toString()),
                "state" to escapeSingleQuotes(paths.state.toString()),
                "log" to escapeSingleQuotes(paths.logFile.toString()),
            ),
        )
        Files.createDirectories(paths.script.parent)
        Files.createDirectories(paths.logFile.parent)
        Files.createDirectories(paths.db)
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

    fun ensureTool(nodeDir: Path): Path?
    {
        return SuiHostBinaries.resolveTool(nodeDir)
            ?: SuiHostBinaries.resolveTool(nodeDir.parent ?: nodeDir)
    }

    fun startProcess(script: Path): Process
    {
        val pb = ProcessBuilder("bash", script.toAbsolutePath().toString())
        pb.redirectErrorStream(true)
        return pb.start()
    }

    fun stopTool()
    {
        try
        {
            ProcessBuilder("pkill", "-f", "sui-tool.*download-formal-snapshot")
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
        catch (e: Exception)
        {
            log.debug("pkill sui-tool: {}", e.message)
        }
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
            log.debug("sui snapshot state parse: {}", e.message)
            null
        }
    }

    fun readLiveProgress(paths: Paths): StateSnapshot?
    {
        val fromLog = SuiFormalSnapshotProgress.read(paths.nodeDir.toString())
            ?: SuiFormalSnapshotProgress.read(paths.db.toString())
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
            phase = fromState?.phase?.ifBlank { "download" } ?: "download",
            pct = fromLog.pct,
            detail = fromLog.detail.ifBlank { fromState?.detail.orEmpty() },
            error = fromState?.error.orEmpty(),
        )
    }

    fun markerReady(marker: Path): Boolean = Files.isRegularFile(marker)

    data class StateSnapshot(
        val phase: String,
        val pct: Double?,
        val detail: String,
        val error: String,
    )

    private fun escapeSingleQuotes(raw: String): String =
        raw.trim().replace("'", "'\\''")
}
