package rpcnode.toolkit.chains.cardano.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoClusters
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoHostBinaries
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Runs the Mithril cardano-db restore through the shipped script (`resources/chains/cardano/mithril-snapshot.sh.tmpl`).
 * [Paths.dest] is the blockchain disk role dir; the database lands in `<dest>/db`.
 */
class CardanoMithrilSnapshotRunner
{
    private val log = LoggerFactory.getLogger(CardanoMithrilSnapshotRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Paths(
        val dest: Path,
        val db: Path,
        val marker: Path,
        val state: Path,
        val logFile: Path,
        val script: Path,
    )

    data class StateSnapshot(val phase: String, val pct: Double?, val detail: String, val error: String)

    fun layout(dest: Path): Paths
    {
        val root = dest.toAbsolutePath().normalize()
        val db = root.resolve("db")
        return Paths(
            dest = root,
            db = db,
            marker = db.resolve(".snapshot-ready"),
            state = root.resolve(".toolkit").resolve("snapshot-state.json"),
            logFile = root.resolve("logs").resolve("cardano-snapshot.log"),
            script = root.resolve(".toolkit").resolve("cardano-mithril-snapshot.sh"),
        )
    }

    fun ensureClient(nodeDir: Path): Path? =
        CardanoHostBinaries.ensureMithril(nodeDir)
            ?: nodeDir.parent?.let { CardanoHostBinaries.ensureMithril(it) }

    fun writeScript(paths: Paths, env: String, client: Path): Path
    {
        val cluster = CardanoClusters.lookup(env)
        val body = HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("cardano", "mithril-snapshot.sh.tmpl"),
            mapOf(
                "env" to esc(cluster.env),
                "client" to esc(client.toAbsolutePath().toString()),
                "dest" to esc(paths.dest.toString()),
                "aggregator" to esc(cluster.aggregator),
                "genesis_url" to esc(cluster.genesisKeyUrl()),
                "ancillary_url" to esc(cluster.ancillaryKeyUrl()),
                "marker" to esc(paths.marker.toString()),
                "state" to esc(paths.state.toString()),
                "log" to esc(paths.logFile.toString()),
            ),
        )
        Files.createDirectories(paths.script.parent)
        Files.createDirectories(paths.logFile.parent)
        Files.writeString(paths.script, body)
        try
        {
            Files.setPosixFilePermissions(
                paths.script,
                setOf(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE,
                    PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                    PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE,
                ),
            )
        }
        catch (_: Exception)
        {
            paths.script.toFile().setExecutable(true)
        }
        return paths.script
    }

    fun startProcess(script: Path, env: Map<String, String> = emptyMap()): Process
    {
        val pb = ProcessBuilder("bash", script.toAbsolutePath().toString())
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        return pb.start()
    }

    fun stopTool()
    {
        try
        {
            ProcessBuilder("pkill", "-f", "mithril-client.*cardano-db").redirectErrorStream(true).start().waitFor()
        }
        catch (e: Exception)
        {
            log.debug("pkill mithril-client: {}", e.message)
        }
    }

    fun readState(statePath: Path): StateSnapshot?
    {
        if (!Files.isRegularFile(statePath)) return null
        return try
        {
            val root = json.parseToJsonElement(Files.readString(statePath)).jsonObject
            StateSnapshot(
                phase = root["phase"]?.jsonPrimitive?.content?.trim().orEmpty().ifBlank { "download" },
                pct = root["pct"]?.jsonPrimitive?.doubleOrNull,
                detail = root["detail"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
                error = root["error"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            )
        }
        catch (e: Exception)
        {
            log.debug("cardano snapshot state parse: {}", e.message)
            null
        }
    }

    fun readLiveProgress(paths: Paths): StateSnapshot?
    {
        val fromLog = CardanoMithrilProgress.read(paths.dest.toString())
        val fromState = readState(paths.state)
        if (fromLog == null) return fromState
        if (fromState?.phase.equals("error", ignoreCase = true)) return fromState
        return StateSnapshot(
            phase = fromState?.phase?.ifBlank { "download" } ?: "download",
            pct = fromLog.pct,
            detail = fromLog.detail.ifBlank { fromState?.detail.orEmpty() },
            error = fromState?.error.orEmpty(),
        )
    }

    fun markerReady(marker: Path): Boolean = Files.isRegularFile(marker)

    private fun esc(raw: String): String = raw.trim().replace("'", "'\\''")
}
