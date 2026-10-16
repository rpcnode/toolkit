package rpcnode.toolkit.chains.starknet.infrastructure

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * Installs Juno from the clients-sync zip in [nodeDir] (`juno-x86_64-linux.zip`). The release zip holds one static ELF named
 * `juno-<tag>-linux-<arch>` and its `.sha256`; the ELF is unpacked in the JVM (no `unzip` on the host), checked against that
 * `.sha256` and written to `bin/juno`. A zip newer than the binary (a client update) is unpacked again.
 */
object StarknetHostBinaries
{
    const val BINARY = "juno"
    const val ARCHIVE_GLOB = "juno-*.zip"

    sealed interface Result
    {
        data class Ok(val juno: Path) : Result
        data class Failed(val detail: String) : Result
    }

    fun ensure(nodeDir: Path): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val bin = root.resolve("bin").resolve(BINARY)
        return try
        {
            val archive = findArchive(root)
            val stale = archive != null && Files.isRegularFile(bin) &&
                Files.getLastModifiedTime(archive) > Files.getLastModifiedTime(bin)
            if (archive != null && (!Files.isRegularFile(bin) || stale))
            {
                extract(archive, bin)
            }
            if (!Files.isRegularFile(bin))
            {
                return Result.Failed("juno missing under $root — sync starknet clients ($ARCHIVE_GLOB) first")
            }
            bin.toFile().setExecutable(true)
            Result.Ok(bin)
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "juno install failed")
        }
    }

    private fun findArchive(root: Path): Path?
    {
        if (!Files.isDirectory(root))
        {
            return null
        }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$ARCHIVE_GLOB")
        return Files.list(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }.sorted().findFirst().orElse(null)
        }
    }

    /** The entry that is the program: `juno-v0.16.8-linux-amd64` (not its `.sha256`). */
    internal fun isBinaryEntry(name: String): Boolean
    {
        val leaf = name.substringAfterLast('/')
        return leaf.startsWith("juno") && !leaf.endsWith(".sha256") && !leaf.endsWith(".zip")
    }

    private fun extract(archive: Path, bin: Path)
    {
        ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.toList()
            val program = entries.firstOrNull { isBinaryEntry(it.name) }
                ?: error("no juno binary inside ${archive.fileName}")
            Files.createDirectories(bin.parent)
            val tmp = bin.resolveSibling("$BINARY.tmp")
            val digest = MessageDigest.getInstance("SHA-256")
            zip.getInputStream(program).use { input ->
                Files.newOutputStream(tmp).use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true)
                    {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
            val expected = entries.firstOrNull { it.name.endsWith(".sha256") }?.let { sum ->
                zip.getInputStream(sum).use { it.readBytes().toString(Charsets.UTF_8) }
            }?.trim()?.substringBefore(' ')?.lowercase()
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != null && expected != actual)
            {
                Files.deleteIfExists(tmp)
                error("juno checksum mismatch in ${archive.fileName}: expected $expected, got $actual")
            }
            tmp.toFile().setExecutable(true)
            Files.move(tmp, bin, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
