package rpcnode.toolkit.chains.bsc.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import rpcnode.toolkit.chains.bsc.infrastructure.BscClusters
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Runs official `bnb-chain/bsc-snapshots` bootstrap via the shipped heal script
 * (`resources/chains/bsc/snapshot-heal.sh.tmpl`). Progress is mirrored from
 * `<datadir>/.snapshot-state.json`.
 */
class BscOfficialSnapshotRunner
{
    private val log = LoggerFactory.getLogger(BscOfficialSnapshotRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    data class Paths(
        val data: Path,
        val snap: Path,
        val opt: Path,
        val marker: Path,
        val state: Path,
        val logFile: Path,
        val script: Path,
    )

    fun layout(dataDir: Path, snapDir: Path?, env: String): Paths
    {
        val data = dataDir.toAbsolutePath().normalize()
        val snap = snapDir?.toAbsolutePath()?.normalize()
            ?: data.resolve("snapshots")
        val envId = BscClusters.lookup(env).env
        val opt = Path.of("/opt/bsc", envId)
        return Paths(
            data = data,
            snap = snap,
            opt = opt,
            marker = data.resolve(".snapshot-ready"),
            state = data.resolve(".snapshot-state.json"),
            logFile = Path.of("/var/log/bsc", "$envId-snapshot.log"),
            script = opt.resolve("bin").resolve("bsc-official-snapshot.sh"),
        )
    }

    fun writeHealScript(
        paths: Paths,
        env: String,
        flavor: String,
    ): Path
    {
        val cluster = BscClusters.lookup(env)
        val pruneFlag = if (flavor == "full") "" else " -p"
        val body = HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("bsc", "snapshot-heal.sh.tmpl"),
            mapOf(
                "env" to escapeSingleQuotes(cluster.env),
                "data" to escapeSingleQuotes(paths.data.toString()),
                "snap" to escapeSingleQuotes(paths.snap.toString()),
                "opt" to escapeSingleQuotes(paths.opt.toString()),
                "marker" to escapeSingleQuotes(paths.marker.toString()),
                "state" to escapeSingleQuotes(paths.state.toString()),
                "log" to escapeSingleQuotes(paths.logFile.toString()),
                "flavor" to escapeSingleQuotes(flavor),
                "prefix" to escapeSingleQuotes(cluster.snapshotPrefix),
                "readme" to escapeSingleQuotes(README),
                "fetch" to escapeSingleQuotes(FETCH),
                "prune_flag" to pruneFlag,
                "toolkit_version" to "kotlin",
            ),
        )
        Files.createDirectories(paths.script.parent)
        Files.createDirectories(paths.logFile.parent)
        Files.createDirectories(paths.snap)
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
            log.debug("bsc snapshot state parse: {}", e.message)
            null
        }
    }

    fun markerReady(marker: Path): Boolean = Files.isRegularFile(marker)

    data class StateSnapshot(
        val phase: String,
        val pct: Double?,
        val detail: String,
        val error: String,
    )

    companion object
    {
        const val README = "https://raw.githubusercontent.com/bnb-chain/bsc-snapshots/main/README.md"
        const val FETCH = "https://raw.githubusercontent.com/bnb-chain/bsc-snapshots/main/dist/fetch-snapshot.sh"

        private fun escapeSingleQuotes(raw: String): String =
            raw.trim().replace("'", "'\\''")
    }
}
