package rpcnode.toolkit.chains.arb.infrastructure

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Installs Nitro from the client-sync tarball in [nodeDir]
 * (`nitro-*.tar.gz` from panel Docker pack → `bin/nitro` + wasm machine roots).
 */
object ArbHostBinaries
{
    const val BINARY = "nitro"
    const val ARCHIVE_GLOB = "nitro-*.tar.gz"

    data class Bins(
        val nitro: Path,
        val wasmRoots: String,
    )

    sealed interface Result
    {
        data class Ok(val bins: Bins) : Result
        data class Failed(val detail: String) : Result
    }

    fun ensure(nodeDir: Path): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        return try
        {
            extractArchive(root)
            val nitro = resolveBin(root)
                ?: return Result.Failed(
                    "nitro missing under $root — sync arb clients (nitro-*.tar.gz) first; " +
                        "archive must contain bin/nitro, nitro-legacy/machines, target/machines",
                )
            nitro.toFile().setExecutable(true)
            val legacy = root.resolve("nitro-legacy").resolve("machines")
            val target = root.resolve("target").resolve("machines")
            if (!Files.isDirectory(legacy) || !Files.isDirectory(target))
            {
                return Result.Failed(
                    "nitro wasm machine roots missing under $root " +
                        "(need nitro-legacy/machines and target/machines inside the nitro tarball)",
                )
            }
            Result.Ok(
                Bins(
                    nitro = nitro,
                    wasmRoots = "${legacy.toAbsolutePath()},${target.toAbsolutePath()}",
                ),
            )
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "nitro install failed")
        }
    }

    private fun resolveBin(root: Path): Path?
    {
        val candidates = listOf(
            root.resolve("bin").resolve(BINARY),
            root.resolve(BINARY),
            root.resolve("usr").resolve("local").resolve("bin").resolve(BINARY),
        )
        candidates.firstOrNull { Files.isRegularFile(it) }?.let { return it }
        if (!Files.isDirectory(root))
        {
            return null
        }
        return Files.walk(root, 5).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == BINARY }
                .findFirst()
                .orElse(null)
        }
    }

    private fun extractArchive(nodeDir: Path)
    {
        if (!Files.isDirectory(nodeDir))
        {
            return
        }
        if (Files.isRegularFile(nodeDir.resolve("bin").resolve(BINARY)))
        {
            return
        }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$ARCHIVE_GLOB")
        val archive = Files.list(nodeDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }
                .findFirst()
                .orElse(null)
        } ?: return
        val pb = ProcessBuilder("tar", "-xzf", archive.fileName.toString())
        pb.directory(nodeDir.toFile())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0)
        {
            throw IllegalStateException("tar extract ${archive.fileName} failed: $out")
        }
        // Normalize: if nitro landed outside bin/, copy into bin/nitro.
        val binDest = nodeDir.resolve("bin").resolve(BINARY)
        if (!Files.isRegularFile(binDest))
        {
            val found = resolveBin(nodeDir) ?: return
            if (found.toAbsolutePath().normalize() != binDest.toAbsolutePath().normalize())
            {
                Files.createDirectories(binDest.parent)
                Files.copy(found, binDest, StandardCopyOption.REPLACE_EXISTING)
                binDest.toFile().setExecutable(true)
            }
        }
    }

    /**
     * Reads the foundation archive-path pointer and returns a directory URL for `--init.url`.
     */
    fun resolveArchivePathUrl(env: String): String?
    {
        val e = ArbClusters.lookup(env).env
        val pointer = if (e == "sepolia")
        {
            ArbClusters.ARCHIVE_POINTER_SEPOLIA
        }
        else
        {
            ArbClusters.ARCHIVE_POINTER_MAINNET
        }
        return try
        {
            URI(pointer).toURL().openStream().use { stream ->
                val rel = stream.readBytes().toString(StandardCharsets.UTF_8).trim()
                    .trimStart('/')
                if (rel.isEmpty())
                {
                    null
                }
                else if (rel.startsWith("http://") || rel.startsWith("https://"))
                {
                    if (rel.endsWith("/")) rel else "$rel/"
                }
                else
                {
                    val path = if (rel.endsWith("/")) rel else "$rel/"
                    ArbClusters.SNAPSHOT_BASE + path
                }
            }
        }
        catch (_: Exception)
        {
            null
        }
    }
}
