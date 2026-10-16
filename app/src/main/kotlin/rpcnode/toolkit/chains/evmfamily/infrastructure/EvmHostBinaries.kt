package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipInputStream

/**
 * Puts the executables of an EVM-family node into `<node_dir>/bin/` from the files the Clients step synced into
 * node_dir (versionless names). Nothing is written outside node_dir. Idempotent: a binary newer than its source stays.
 */
object EvmHostBinaries
{
    sealed interface Result
    {
        data class Ok(val bins: Map<String, Path>) : Result
        data class Failed(val detail: String) : Result
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    /** Shared libraries a `.deb` bundle ships (`usr/local/lib`); units point `LD_LIBRARY_PATH` here. */
    fun libDir(nodeDir: Path): Path = nodeDir.resolve("lib")

    fun ensure(nodeDir: Path, binaries: List<EvmBinary>): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val out = linkedMapOf<String, Path>()
        val debDone = mutableSetOf<String>()
        return try
        {
            for (b in binaries)
            {
                val dest = binDir(root).resolve(b.name)
                if (b.kind == EvmBinary.Kind.DEB_BUNDLE)
                {
                    if (debDone.add(b.synced))
                    {
                        ensureDeb(root, binaries.filter { it.kind == EvmBinary.Kind.DEB_BUNDLE && it.synced == b.synced })
                            ?.let { return Result.Failed(it) }
                    }
                    if (!Files.isExecutable(dest)) return Result.Failed("${b.name} not found in the ${b.synced} package")
                    out[b.name] = dest
                    continue
                }
                val src = findSynced(root, b.synced)
                if (src == null)
                {
                    if (Files.isExecutable(dest))
                    {
                        out[b.name] = dest
                        continue
                    }
                    return Result.Failed("${b.name}: ${b.synced} missing under $root — sync clients first")
                }
                val fresh = Files.isExecutable(dest) && Files.getLastModifiedTime(dest) >= Files.getLastModifiedTime(src)
                if (!fresh)
                {
                    install(b, src, dest)
                }
                if (!Files.isExecutable(dest))
                {
                    return Result.Failed("${b.name} not found in ${src.fileName}")
                }
                out[b.name] = dest
            }
            Result.Ok(out)
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "binary install failed")
        }
    }

    /**
     * One pass over the `.deb` for all [group] binaries plus `usr/local/lib`. Returns the failure text, null when the binaries
     * and libraries are in place (freshly unpacked, or already newer than the package).
     */
    private fun ensureDeb(root: Path, group: List<EvmBinary>): String?
    {
        val glob = group.first().synced
        val src = findSynced(root, glob)
        val bin = binDir(root)
        val lib = libDir(root)
        if (src == null)
        {
            return if (group.all { Files.isExecutable(bin.resolve(it.name)) } && Files.isDirectory(lib)) null
            else "$glob missing under $root — sync clients first"
        }
        val srcTime = Files.getLastModifiedTime(src)
        val fresh = Files.isDirectory(lib) && group.all {
            val d = bin.resolve(it.name)
            Files.isExecutable(d) && Files.getLastModifiedTime(d) >= srcTime
        }
        if (fresh) return null
        Files.createDirectories(bin)
        Files.createDirectories(lib)
        val tmp = Files.createTempDirectory(root, ".deb-")
        try
        {
            val members = group.map { "./usr/local/bin/${it.member}" } + "./usr/local/lib"
            val cmd = listOf(
                "bash", "-o", "pipefail", "-c",
                "dpkg-deb --fsys-tarfile \"$1\" | tar -xf - -C \"$2\" \"\${@:3}\"",
                "_", src.toString(), tmp.toString(),
            ) + members
            val p = try
            {
                ProcessBuilder(cmd).redirectErrorStream(true).start()
            }
            catch (e: java.io.IOException)
            {
                return "dpkg-deb not found (Debian/Ubuntu host required)"
            }
            val log = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            if (p.waitFor() != 0) return "unpacking ${src.fileName} failed: ${log.take(300)}"
            for (b in group)
            {
                val found = tmp.resolve("usr/local/bin/${b.member}")
                if (!Files.isRegularFile(found)) return "${b.member} not found in ${src.fileName}"
                copyExecutable(found, bin.resolve(b.name))
            }
            val libs = tmp.resolve("usr/local/lib")
            if (Files.isDirectory(libs))
            {
                Files.list(libs).use { s ->
                    s.filter { Files.isRegularFile(it) }.forEach { copyExecutable(it, lib.resolve(it.fileName.toString())) }
                }
            }
            for (b in group) Files.setLastModifiedTime(bin.resolve(b.name), srcTime)
            return null
        }
        finally
        {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    private fun findSynced(root: Path, glob: String): Path?
    {
        if (!Files.isDirectory(root)) return null
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        Files.list(root).use { s ->
            return s.filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }.sorted().findFirst().orElse(null)
        }
    }

    private fun install(b: EvmBinary, src: Path, dest: Path)
    {
        Files.createDirectories(dest.parent)
        when (b.kind)
        {
            EvmBinary.Kind.RAW -> copyExecutable(src, dest)
            EvmBinary.Kind.TAR_MEMBER -> withTemp(dest) { tmp ->
                val p = ProcessBuilder("tar", "-xzf", src.toString(), "-C", tmp.toString()).redirectErrorStream(true).start()
                val log = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                check(p.waitFor() == 0) { "tar -xzf ${src.fileName} failed: ${log.take(300)}" }
                copyExecutable(findMember(tmp, b.member) ?: error("${b.member} not found in ${src.fileName}"), dest)
            }
            EvmBinary.Kind.ZIP_MEMBER -> withTemp(dest) { tmp ->
                unzip(src, tmp)
                copyExecutable(findMember(tmp, b.member) ?: error("${b.member} not found in ${src.fileName}"), dest)
            }
            EvmBinary.Kind.ZIP_BUNDLE -> unzip(src, dest.parent, executableByName = b.name)
            EvmBinary.Kind.DEB_BUNDLE -> error("${b.name}: deb bundles are installed by ensureDeb")
        }
        Files.setLastModifiedTime(dest, Files.getLastModifiedTime(src))
    }

    private inline fun withTemp(dest: Path, block: (Path) -> Unit)
    {
        val tmp = Files.createTempDirectory(dest.parent, ".extract-")
        try
        {
            block(tmp)
        }
        finally
        {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    internal fun findMember(root: Path, glob: String): Path?
    {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        return Files.walk(root, 6).use { s ->
            s.filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }.sorted().findFirst().orElse(null)
        }
    }

    private fun copyExecutable(src: Path, dest: Path)
    {
        val tmp = dest.resolveSibling("${dest.fileName}.tmp")
        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x")) }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    /** Java unzip (no `unzip` package needed); entries that would leave [dest] are skipped. */
    internal fun unzip(zip: Path, dest: Path, executableByName: String? = null)
    {
        val base = dest.toAbsolutePath().normalize()
        ZipInputStream(Files.newInputStream(zip)).use { zis ->
            while (true)
            {
                val entry = zis.nextEntry ?: break
                val out = base.resolve(entry.name).normalize()
                if (!out.startsWith(base)) continue
                if (entry.isDirectory)
                {
                    Files.createDirectories(out)
                    continue
                }
                Files.createDirectories(out.parent)
                Files.copy(zis, out, StandardCopyOption.REPLACE_EXISTING)
                // zip entries carry no unix mode: the entry point and extension-less files (the native executables) run
                if (out.fileName.toString() == executableByName || '.' !in out.fileName.toString())
                {
                    runCatching { Files.setPosixFilePermissions(out, PosixFilePermissions.fromString("rwxr-xr-x")) }
                }
            }
        }
    }
}
