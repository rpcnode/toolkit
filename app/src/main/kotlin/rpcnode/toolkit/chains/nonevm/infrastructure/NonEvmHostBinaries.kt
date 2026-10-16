package rpcnode.toolkit.chains.nonevm.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Puts a client's executables into `<node_dir>/bin/`, the one place every unit of the family runs them from.
 *
 * Two shapes of release are handled: bare binaries synced to the node dir under their own names ([Source.Raw]: polkadot,
 * neard), and an archive ([Source.Archive]: stacks and aleo `.zip`, fuel `.tar.gz`) whose members are found by file name
 * anywhere in the tree. The synced file is kept (client update / rollback work on it); the copy in `bin/` is refreshed
 * whenever the synced file is newer, so a client update takes effect at the next Start.
 */
object NonEvmHostBinaries
{
    enum class ArchiveKind { ZIP, TAR_GZ }

    sealed interface Source
    {
        /** Files named like the wanted names sit at the top of the node dir. */
        data object Raw : Source

        /** The first file matching [glob] is an archive of [kind]. */
        data class Archive(val glob: String, val kind: ArchiveKind) : Source
    }

    sealed interface Result
    {
        data class Ok(val bin: Path) : Result
        data class Failed(val detail: String) : Result
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    /** [names]: file names that must end up in `bin/`; [extra]: optional files taken from the same archive when present. */
    fun ensure(nodeDir: Path, names: List<String>, source: Source, extra: List<String> = emptyList()): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val bin = binDir(root)
        return try
        {
            when (source)
            {
                Source.Raw -> fromRaw(root, bin, names)
                is Source.Archive -> fromArchive(root, bin, names, extra, source)
            }
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "binary install failed")
        }
    }

    private val ARCH_SUFFIX = Regex("^(x86_64|aarch64)$")

    /**
     * The synced file of [name]: the file of that name, or `<name>-x86_64` / `<name>-aarch64` — the panel keeps both
     * architectures of a binary under distinct names and syncs only the host's, so at most one exists.
     */
    private fun rawFile(root: Path, name: String): Path?
    {
        val exact = root.resolve(name)
        if (Files.isRegularFile(exact)) return exact
        return Files.newDirectoryStream(root, "$name-*").use { s ->
            s.filter { Files.isRegularFile(it) && ARCH_SUFFIX.matches(it.fileName.toString().removePrefix("$name-")) }
                .minByOrNull { it.fileName.toString() }
        }
    }

    private fun fromRaw(root: Path, bin: Path, names: List<String>): Result
    {
        Files.createDirectories(bin)
        for (name in names)
        {
            val raw = rawFile(root, name)
            val dest = bin.resolve(name)
            when
            {
                raw != null && (!Files.isExecutable(dest) || newer(raw, dest)) -> install(raw, dest)
                Files.isExecutable(dest) -> Unit
                else -> return Result.Failed("$name missing under $root — sync clients first")
            }
        }
        return Result.Ok(bin)
    }

    private fun fromArchive(root: Path, bin: Path, names: List<String>, extra: List<String>, source: Source.Archive): Result
    {
        val archive = Files.newDirectoryStream(root, source.glob).use { s ->
            s.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }.firstOrNull()
        }
        if (archive == null)
        {
            return if (names.all { Files.isExecutable(bin.resolve(it)) }) Result.Ok(bin)
            else Result.Failed("${source.glob} missing under $root — sync clients first")
        }
        if (names.all { Files.isExecutable(bin.resolve(it)) } && names.all { !newer(archive, bin.resolve(it)) })
        {
            return Result.Ok(bin)
        }
        val tmp = Files.createTempDirectory(root, ".extract-")
        try
        {
            val cmd = when (source.kind)
            {
                ArchiveKind.ZIP -> listOf("unzip", "-oq", archive.toString(), "-d", tmp.toString())
                ArchiveKind.TAR_GZ -> listOf("tar", "-xzf", archive.toString(), "-C", tmp.toString())
            }
            val p = try
            {
                ProcessBuilder(cmd).redirectErrorStream(true).start()
            }
            catch (e: java.io.IOException)
            {
                return Result.Failed("${cmd.first()} is required to unpack ${archive.fileName} — install the ${cmd.first()} package")
            }
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            if (p.waitFor() != 0)
            {
                return Result.Failed("extract ${archive.fileName} failed: ${out.take(300)}")
            }
            val found = Files.walk(tmp).use { s -> s.filter { Files.isRegularFile(it) }.toList() }
            Files.createDirectories(bin)
            for (name in names)
            {
                val member = found.firstOrNull { it.fileName.toString() == name }
                    ?: return Result.Failed("$name not found in ${archive.fileName}")
                install(member, bin.resolve(name))
            }
            for (name in extra)
            {
                found.firstOrNull { it.fileName.toString() == name }?.let { install(it, bin.resolve(name)) }
            }
            return Result.Ok(bin)
        }
        finally
        {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    private fun newer(src: Path, dest: Path): Boolean =
        !Files.exists(dest) || Files.getLastModifiedTime(src) > Files.getLastModifiedTime(dest)

    private fun install(src: Path, dest: Path)
    {
        val tmp = dest.resolveSibling("${dest.fileName}.tmp")
        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x")) }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Runs `<binary> --version` and returns the trimmed output; null when it does not run (wrong libc / arch) or exits non-zero. */
    fun versionOutput(binary: Path): String?
    {
        return try
        {
            val p = ProcessBuilder(binary.toString(), "--version").redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) null else out
        }
        catch (_: Exception)
        {
            null
        }
    }
}
