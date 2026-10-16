package rpcnode.toolkit.chains.aptos.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Extracts `aptos-node` (a single 1.8 GiB binary at the root of the release tarball) straight into `{nodeDir}/bin/`.
 * x86_64 only: aptos-labs publishes no aarch64 build.
 */
object AptosHostBinaries
{
    const val NODE = "aptos-node"
    const val ARCHIVE_GLOB = "aptos-node-performance-ubuntu-*.tgz"
    const val MIN_FREE_BYTES = 2_500_000_000L

    sealed interface Result
    {
        data class Ok(val node: Path) : Result
        data class Failed(val detail: String) : Result
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    fun archSupported(osArch: String): Boolean = osArch.lowercase().let { it == "amd64" || it == "x86_64" }

    fun ensure(nodeDir: Path, osArch: String = System.getProperty("os.arch").orEmpty()): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val dest = binDir(root)
        val node = dest.resolve(NODE)
        if (Files.isRegularFile(node) && Files.isExecutable(node))
        {
            return Result.Ok(node)
        }
        if (!archSupported(osArch))
        {
            return Result.Failed("aptos-node ships x86_64 builds only (no aarch64 release)")
        }
        val archive = Files.newDirectoryStream(root, ARCHIVE_GLOB).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }.firstOrNull()
        } ?: return Result.Failed("aptos-node tarball missing under $root — sync clients (aptos-labs/aptos-core) first")
        return try
        {
            Files.createDirectories(dest)
            if (Files.getFileStore(dest).usableSpace < MIN_FREE_BYTES)
            {
                return Result.Failed("need ~2.5 GB free under $dest to extract aptos-node (1.8 GB binary)")
            }
            val pb = ProcessBuilder("tar", "-xzf", archive.toString(), "-C", dest.toString(), NODE).redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code != 0)
            {
                return Result.Failed("tar extract failed ($code): ${out.take(800)}")
            }
            runCatching { Files.setPosixFilePermissions(node, PosixFilePermissions.fromString("rwxr-xr-x")) }
            if (Files.isRegularFile(node) && Files.isExecutable(node)) Result.Ok(node) else Result.Failed("aptos-node missing after extract")
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "aptos-node extract failed")
        }
    }
}
