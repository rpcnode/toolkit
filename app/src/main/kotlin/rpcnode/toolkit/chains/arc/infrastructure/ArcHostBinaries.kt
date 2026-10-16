package rpcnode.toolkit.chains.arc.infrastructure

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Installs `arc-node-execution`, `arc-node-consensus` and `arc-snapshots` from the circlefin/arc-node release tarball under
 * `{nodeDir}/bin/`. Never writes `/opt/arc`.
 */
object ArcHostBinaries
{
    const val EXECUTION = "arc-node-execution"
    const val CONSENSUS = "arc-node-consensus"
    const val SNAPSHOTS = "arc-snapshots"
    private val ALL = listOf(EXECUTION, CONSENSUS, SNAPSHOTS)
    private const val ARCHIVE_GLOB = "arc-node-linux-*.tar.gz"

    sealed interface Result
    {
        data class Ok(val binDir: Path) : Result
        data class Failed(val detail: String) : Result
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    fun ensure(nodeDir: Path): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val dest = binDir(root)
        if (ALL.all { ready(dest.resolve(it)) })
        {
            return Result.Ok(dest)
        }
        return try
        {
            Files.createDirectories(dest)
            val archive = findArchive(root)
                ?: return Result.Failed("Arc tarball missing under $root — sync clients (circlefin/arc-node) first")
            extract(archive, dest)
            val missing = ALL.filter { !ready(dest.resolve(it)) }
            if (missing.isNotEmpty())
            {
                return Result.Failed("${missing.joinToString()} not found after extracting $archive")
            }
            Result.Ok(dest)
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "arc binary install failed")
        }
    }

    private fun ready(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)

    private fun findArchive(root: Path): Path?
    {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$ARCHIVE_GLOB")
        if (!Files.isDirectory(root))
        {
            return null
        }
        Files.list(root).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }
                .sorted()
                .findFirst()
                .orElse(null)
        }
    }

    private fun extract(archive: Path, dest: Path)
    {
        val tmp = Files.createTempDirectory("arc-extract-")
        try
        {
            val p = ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(), "-C", tmp.toString())
                .redirectErrorStream(true)
                .start()
            val out = p.inputStream.bufferedReader().readText().take(800)
            val code = p.waitFor()
            if (code != 0)
            {
                error("tar extract failed ($code): $out")
            }
            for (name in ALL)
            {
                val from = find(tmp, name) ?: continue
                val to = dest.resolve(name)
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
                makeExecutable(to)
            }
        }
        finally
        {
            runCatching {
                Files.walk(tmp).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    /** The release may keep the binaries at the top or under a folder (`bin/`, `arc-node-<tag>/`). */
    internal fun find(root: Path, name: String): Path?
    {
        Files.walk(root, 4).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == name }
                .findFirst()
                .orElse(null)
        }
    }

    private fun makeExecutable(path: Path)
    {
        try
        {
            Files.setPosixFilePermissions(
                path,
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
            path.toFile().setExecutable(true)
        }
    }
}
