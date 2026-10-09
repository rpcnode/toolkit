package rpcnode.toolkit.chains.sui.infrastructure

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import org.slf4j.LoggerFactory

/**
 * Installs `sui-node` / `sui-tool` / `sui` from the Mysten ubuntu tarball under
 * `{nodeDir}/bin/`. Never writes `/opt/sui`.
 */
object SuiHostBinaries
{
    private val log = LoggerFactory.getLogger(SuiHostBinaries::class.java)

    const val NODE = "sui-node"
    const val TOOL = "sui-tool"
    const val CLI = "sui"
    private const val ARCHIVE_GLOB = "sui-*.tgz"

    data class Bins(val node: Path, val tool: Path?)

    sealed interface Result
    {
        data class Ok(val bins: Bins) : Result
        data class Failed(val detail: String) : Result
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    fun ensure(nodeDir: Path): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val dest = binDir(root)
        val nodeDest = dest.resolve(NODE)
        if (ready(nodeDest))
        {
            val tool = dest.resolve(TOOL).takeIf { ready(it) }
            return Result.Ok(Bins(node = nodeDest, tool = tool))
        }
        return try
        {
            Files.createDirectories(dest)
            val archive = findArchive(root)
                ?: return Result.Failed(
                    "Sui tarball missing under $root — sync clients (MystenLabs/sui) first",
                )
            extractBins(archive, dest)
            if (!ready(nodeDest))
            {
                return Result.Failed("sui-node not found after extracting $archive")
            }
            Result.Ok(
                Bins(
                    node = nodeDest,
                    tool = dest.resolve(TOOL).takeIf { ready(it) },
                ),
            )
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "sui binary install failed")
        }
    }

    fun resolveTool(nodeDir: Path): Path?
    {
        return when (val r = ensure(nodeDir))
        {
            is Result.Ok -> r.bins.tool
            is Result.Failed ->
            {
                log.warn("sui-tool: {}", r.detail)
                null
            }
        }
    }

    private fun ready(path: Path): Boolean =
        Files.isRegularFile(path) && Files.isExecutable(path)

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

    private fun extractBins(archive: Path, destBin: Path)
    {
        val tmp = Files.createTempDirectory("sui-extract-")
        try
        {
            val pb = ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(), "-C", tmp.toString())
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().take(800)
            val code = p.waitFor()
            if (code != 0)
            {
                error("tar extract failed ($code): $out")
            }
            val src = findBinSource(tmp) ?: error("sui-node binary not found in tarball")
            for (name in listOf(NODE, TOOL, CLI))
            {
                val from = src.resolve(name)
                if (!Files.isRegularFile(from))
                {
                    continue
                }
                val to = destBin.resolve(name)
                Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
                makeExecutable(to)
            }
        }
        finally
        {
            runCatching {
                Files.walk(tmp).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
        }
    }

    private fun findBinSource(extractRoot: Path): Path?
    {
        val direct = extractRoot.resolve(NODE)
        if (Files.isRegularFile(direct))
        {
            return extractRoot
        }
        val bin = extractRoot.resolve("bin").resolve(NODE)
        if (Files.isRegularFile(bin))
        {
            return extractRoot.resolve("bin")
        }
        Files.walk(extractRoot, 3).use { stream ->
            val hit = stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == NODE }
                .findFirst()
                .orElse(null)
            return hit?.parent
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
