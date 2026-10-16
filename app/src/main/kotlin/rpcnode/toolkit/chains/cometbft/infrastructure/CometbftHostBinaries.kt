package rpcnode.toolkit.chains.cometbft.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Puts the chain binary (and any `*.so` shipped beside it, e.g. Injective's libwasmvm) into `<node_dir>/bin/`.
 * A raw release binary is moved there; an archive is extracted whenever it is newer than the installed binary.
 */
object CometbftHostBinaries
{
    sealed interface Result
    {
        data class Ok(val binary: Path) : Result
        data class Failed(val detail: String) : Result
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    fun ensure(nodeDir: Path, profile: CometbftProfile): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val bin = binDir(root).resolve(profile.binary)
        val archive = profile.archiveGlob?.let { glob ->
            Files.newDirectoryStream(root, glob).use { s ->
                s.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }.firstOrNull()
            }
        }
        return try
        {
            when
            {
                profile.archiveKind == CometbftProfile.ArchiveKind.NONE -> fromRaw(root, bin, profile)
                archive == null ->
                    if (Files.isExecutable(bin)) Result.Ok(bin)
                    else Result.Failed("${profile.binary} archive (${profile.archiveGlob}) missing under $root — sync clients first")
                Files.isExecutable(bin) && Files.getLastModifiedTime(bin) >= Files.getLastModifiedTime(archive) -> Result.Ok(bin)
                else -> fromArchive(root, bin, archive, profile)
            }
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "${profile.binary} install failed")
        }
    }

    private fun fromRaw(root: Path, bin: Path, profile: CometbftProfile): Result
    {
        val raw = root.resolve(profile.binary)
        if (Files.isRegularFile(raw))
        {
            Files.createDirectories(bin.parent)
            install(raw, bin)
            Files.deleteIfExists(raw)
            return Result.Ok(bin)
        }
        return if (Files.isExecutable(bin)) Result.Ok(bin)
        else Result.Failed("${profile.binary} missing under $root — sync clients first")
    }

    private fun fromArchive(root: Path, bin: Path, archive: Path, profile: CometbftProfile): Result
    {
        val tmp = Files.createTempDirectory(root, ".cometbft-extract-")
        try
        {
            val cmd = when (profile.archiveKind)
            {
                CometbftProfile.ArchiveKind.ZIP -> listOf("unzip", "-oq", archive.toString(), "-d", tmp.toString())
                else -> listOf("tar", "-xzf", archive.toString(), "-C", tmp.toString())
            }
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            if (p.waitFor() != 0)
            {
                return Result.Failed("extract ${archive.fileName} failed: ${out.take(300)}")
            }
            val found = Files.walk(tmp).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            val binary = found.firstOrNull { it.fileName.toString() == profile.binary }
                ?: return Result.Failed("${profile.binary} not found in ${archive.fileName}")
            Files.createDirectories(bin.parent)
            found.filter { it.fileName.toString().endsWith(".so") }.forEach { install(it, bin.parent.resolve(it.fileName.toString())) }
            install(binary, bin)
            return Result.Ok(bin)
        }
        finally
        {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    private fun install(src: Path, dest: Path)
    {
        val tmp = dest.resolveSibling("${dest.fileName}.tmp")
        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x")) }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
