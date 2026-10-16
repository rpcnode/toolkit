package rpcnode.toolkit.chains.avalanche.infrastructure

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Installs `avalanchego` (and the `plugins/` directory when the tarball carries one) from the GitHub release under
 * `{nodeDir}/bin/`. The C-Chain EVM is built into avalanchego, plugins are only for extra VMs. Never writes `/opt/avalanche`.
 */
object AvalancheHostBinaries
{
    const val NODE = "avalanchego"
    private const val ARCHIVE_GLOB = "avalanchego-linux-*.tar.gz"

    sealed interface Result
    {
        data class Ok(val node: Path) : Result
        data class Failed(val detail: String) : Result
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    fun ensure(nodeDir: Path, pluginsDest: Path): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val dest = binDir(root)
        val nodeDest = dest.resolve(NODE)
        if (ready(nodeDest))
        {
            return Result.Ok(nodeDest)
        }
        return try
        {
            Files.createDirectories(dest)
            val archive = findArchive(root)
                ?: return Result.Failed("avalanchego tarball missing under $root — sync clients (ava-labs/avalanchego) first")
            extract(archive, nodeDest, pluginsDest)
            if (!ready(nodeDest))
            {
                return Result.Failed("avalanchego not found after extracting $archive")
            }
            Result.Ok(nodeDest)
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "avalanchego install failed")
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

    private fun extract(archive: Path, nodeDest: Path, pluginsDest: Path)
    {
        val tmp = Files.createTempDirectory("avalanchego-extract-")
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
            val binary = findBinary(tmp) ?: error("avalanchego binary not found in tarball")
            Files.copy(binary, nodeDest, StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(nodeDest)
            val plugins = binary.parent.resolve("plugins")
            if (Files.isDirectory(plugins))
            {
                Files.createDirectories(pluginsDest)
                Files.list(plugins).use { stream ->
                    stream.filter { Files.isRegularFile(it) }.forEach {
                        val to = pluginsDest.resolve(it.fileName.toString())
                        Files.copy(it, to, StandardCopyOption.REPLACE_EXISTING)
                        makeExecutable(to)
                    }
                }
            }
        }
        finally
        {
            runCatching {
                Files.walk(tmp).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) } }
            }
        }
    }

    /** The release unpacks to `avalanchego-<tag>/avalanchego`. */
    internal fun findBinary(extractRoot: Path): Path?
    {
        Files.walk(extractRoot, 3).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == NODE }
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
