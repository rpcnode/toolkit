package rpcnode.toolkit.chains.etc.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipInputStream

/**
 * Installs `geth` from the core-geth release zip (`core-geth-linux.zip` / `core-geth-arm64.zip` in the node dir) as
 * `{nodeDir}/bin/geth`. The release is a zip, not a tarball, so the shared tar extractor does not apply. Never writes `/opt`.
 */
object EtcHostBinaries
{
    const val NODE = "geth"
    private const val STAMP = ".toolkit/etc-geth.stamp"

    sealed interface Result
    {
        data class Ok(val node: Path) : Result
        data class Failed(val detail: String) : Result
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    fun ensure(nodeDir: Path): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val node = binDir(root).resolve(NODE)
        val zip = findArchive(root)
            ?: return Result.Failed("core-geth zip missing under $root — sync clients (ethereumclassic/core-geth) first")
        val stampFile = root.resolve(STAMP)
        val stamp = "${zip.fileName}:${Files.size(zip)}:${Files.getLastModifiedTime(zip).toMillis()}"
        val upToDate = Files.isExecutable(node) &&
            runCatching { Files.readString(stampFile).trim() }.getOrNull() == stamp
        if (upToDate)
        {
            return Result.Ok(node)
        }
        return try
        {
            extract(zip, node)
            Files.createDirectories(stampFile.parent)
            Files.writeString(stampFile, "$stamp\n")
            Result.Ok(node)
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "core-geth extract failed")
        }
    }

    private fun findArchive(root: Path): Path?
    {
        return Files.newDirectoryStream(root, "core-geth-*.zip").use { stream ->
            stream.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }.firstOrNull()
        }
    }

    private fun extract(zip: Path, node: Path)
    {
        Files.createDirectories(node.parent)
        val tmp = node.resolveSibling("$NODE.tmp")
        var found = false
        ZipInputStream(Files.newInputStream(zip)).use { input ->
            while (true)
            {
                val entry = input.nextEntry ?: break
                val name = entry.name
                if (entry.isDirectory || name.contains("..") || name.startsWith("/"))
                {
                    continue
                }
                if (name.substringAfterLast('/') != NODE)
                {
                    continue
                }
                Files.copy(input, tmp, StandardCopyOption.REPLACE_EXISTING)
                found = true
                break
            }
        }
        if (!found)
        {
            Files.deleteIfExists(tmp)
            throw IllegalStateException("core-geth zip has no `$NODE` binary: ${zip.fileName}")
        }
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x")) }
        Files.move(tmp, node, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
