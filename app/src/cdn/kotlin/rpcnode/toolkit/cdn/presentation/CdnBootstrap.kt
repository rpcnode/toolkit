package rpcnode.toolkit.cdn.presentation

import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * Resolve config: env → env-file.
 * [CdnConfig.snapshotDir] is null until the operator picks a download disk (install / menu).
 */
object CdnBootstrap
{
    private val log = LoggerFactory.getLogger(CdnBootstrap::class.java)

    fun load(
        env: (String) -> String? = { System.getenv(it) },
        defaultDir: Path = CdnPaths.installRoot(),
    ): CdnConfig
    {
        val file = envFilePath(env, defaultDir)
        val stored = CdnEnvFile.read(file)
        val snapshotDir = env("SNAPSHOT_CDN_DIR")?.trim()?.ifEmpty { null }
            ?: stored?.snapshotDir?.trim()?.ifEmpty { null }
        val pollSec = (env("CDN_POLL_SEC") ?: stored?.pollSec)?.toLongOrNull()?.coerceAtLeast(15)
            ?: 60
        val downloadJobs = (env("CDN_DOWNLOAD_JOBS") ?: stored?.downloadJobs)?.toIntOrNull()?.coerceIn(1, 16)
            ?: 4
        val targetsFile = env("CDN_TARGETS_FILE")?.trim()?.ifEmpty { null }
            ?: stored?.targetsFile?.trim()?.ifEmpty { null }
            ?: defaultTargetsPath(env, defaultDir).toString()
        val publicOrigin = env("CDN_PUBLIC_ORIGIN")?.trim()?.ifEmpty { null }
            ?: stored?.publicOrigin?.trim()?.ifEmpty { null }
        if (!Files.isRegularFile(file))
        {
            CdnEnvFile.write(
                file,
                CdnEnvValues(
                    snapshotDir = snapshotDir,
                    pollSec = pollSec.toString(),
                    downloadJobs = downloadJobs.toString(),
                    targetsFile = targetsFile,
                    publicOrigin = publicOrigin,
                ),
            )
            log.info("wrote defaults to {}", file)
        }
        return CdnConfig(
            snapshotDir = snapshotDir,
            pollSec = pollSec,
            downloadJobs = downloadJobs,
            targetsFile = targetsFile,
            envFile = file.toString(),
            publicOrigin = publicOrigin,
        )
    }

    fun envFilePath(env: (String) -> String? = { System.getenv(it) }, defaultDir: Path): Path
    {
        val fromEnv = env("CDN_ENV_FILE")?.trim()?.ifEmpty { null }
        if (fromEnv != null)
        {
            return Path.of(fromEnv)
        }
        val etc = Path.of("/etc/rpcnode/rpcnode-cdn.env")
        if (Files.isRegularFile(etc) || writableParent(etc))
        {
            return etc
        }
        return defaultDir.resolve("rpcnode-cdn.env")
    }

    fun defaultTargetsPath(env: (String) -> String? = { System.getenv(it) }, defaultDir: Path): Path
    {
        val fromEnv = env("CDN_TARGETS_FILE")?.trim()?.ifEmpty { null }
        if (fromEnv != null)
        {
            return Path.of(fromEnv)
        }
        val etc = Path.of("/etc/rpcnode/rpcnode-cdn.targets.json")
        if (Files.isRegularFile(etc) || writableParent(etc))
        {
            return etc
        }
        return defaultDir.resolve("rpcnode-cdn.targets.json")
    }

    private fun writableParent(path: Path): Boolean
    {
        val parent = path.parent ?: return false
        return try
        {
            Files.isDirectory(parent) && Files.isWritable(parent)
        }
        catch (_: Exception)
        {
            false
        }
    }
}
