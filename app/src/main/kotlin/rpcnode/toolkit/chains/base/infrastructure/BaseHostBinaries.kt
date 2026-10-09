package rpcnode.toolkit.chains.base.infrastructure

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import org.slf4j.LoggerFactory

/**
 * Installs `base-reth-node` + `base-consensus` from GitHub release tarballs
 * already synced under [searchRoots] into `/opt/base/<env>/bin/`.
 */
object BaseHostBinaries
{
    private val log = LoggerFactory.getLogger(BaseHostBinaries::class.java)

    data class Bins(val reth: Path, val consensus: Path)

    sealed interface Result
    {
        data class Ok(val bins: Bins) : Result
        data class Failed(val detail: String) : Result
    }

    fun ensure(env: String, vararg searchRoots: Path): Result
    {
        val envId = BaseClusters.lookup(env).env
        val binDir = Path.of("/opt/base", envId, "bin")
        val previousDir = Path.of("/opt/base", envId, "bin.previous")
        val rethDest = binDir.resolve("base-reth-node")
        val consDest = binDir.resolve("base-consensus")
        return try
        {
            runCatching { Files.createDirectories(binDir) }
            val roots = expandSearchRoots(searchRoots.toList() + binDir.parent + Path.of("/usr/local/bin"))
            val reth = installBinary(
                name = "base-reth-node",
                archiveGlob = "base-reth-node-*.tar.gz",
                dest = rethDest,
                previousDir = previousDir,
                roots = roots,
            ) ?: return Result.Failed(
                "base-reth-node missing — sync base clients (GitHub base/base) into the node dir first " +
                    "(looked in: ${roots.joinToString()})",
            )
            val consensus = installBinary(
                name = "base-consensus",
                archiveGlob = "base-consensus-*.tar.gz",
                dest = consDest,
                previousDir = previousDir,
                roots = roots,
            ) ?: return Result.Failed(
                "base-consensus missing — sync base clients (GitHub base/base) into the node dir first " +
                    "(looked in: ${roots.joinToString()})",
            )
            linkLocal(reth, Path.of("/usr/local/bin/base-reth-node"))
            linkLocal(consensus, Path.of("/usr/local/bin/base-consensus"))
            Result.Ok(Bins(reth = reth, consensus = consensus))
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "base binary install failed")
        }
    }

    fun resolveReth(env: String, vararg searchRoots: Path): Path?
    {
        return when (val r = ensure(env, *searchRoots))
        {
            is Result.Ok -> r.bins.reth
            is Result.Failed ->
            {
                log.warn("base-reth-node: {}", r.detail)
                null
            }
        }
    }

    private fun ready(path: Path): Boolean =
        Files.isRegularFile(path) && Files.isExecutable(path)

    /** Include each root and its parent (execution leaf vs network root). */
    private fun expandSearchRoots(roots: List<Path>): List<Path>
    {
        val out = linkedSetOf<Path>()
        for (raw in roots)
        {
            val abs = try
            {
                raw.toAbsolutePath().normalize()
            }
            catch (_: Exception)
            {
                continue
            }
            out.add(abs)
            abs.parent?.let { out.add(it) }
        }
        return out.toList()
    }

    private fun installBinary(
        name: String,
        archiveGlob: String,
        dest: Path,
        previousDir: Path,
        roots: List<Path>,
    ): Path?
    {
        val archive = findArchive(roots, archiveGlob)
        if (ready(dest) && (archive == null || !isNewer(archive, dest)))
        {
            return dest
        }
        for (root in roots)
        {
            val direct = root.resolve(name)
            if (ready(direct) && direct.toAbsolutePath().normalize() != dest.toAbsolutePath().normalize())
            {
                backupPrevious(dest, previousDir)
                return copyExec(direct, dest)
            }
            val nested = root.resolve("bin").resolve(name)
            if (ready(nested) && nested.toAbsolutePath().normalize() != dest.toAbsolutePath().normalize())
            {
                backupPrevious(dest, previousDir)
                return copyExec(nested, dest)
            }
        }
        for (root in roots)
        {
            if (!Files.isDirectory(root))
            {
                continue
            }
            extractMatching(root, archiveGlob)
            val found = findNamed(root, name) ?: continue
            if (found.toAbsolutePath().normalize() == dest.toAbsolutePath().normalize())
            {
                continue
            }
            backupPrevious(dest, previousDir)
            return copyExec(found, dest)
        }
        return if (ready(dest)) dest else null
    }

    private fun backupPrevious(dest: Path, previousDir: Path)
    {
        if (!ready(dest))
        {
            return
        }
        try
        {
            Files.createDirectories(previousDir)
            Files.copy(dest, previousDir.resolve(dest.fileName), StandardCopyOption.REPLACE_EXISTING)
            previousDir.resolve(dest.fileName).toFile().setExecutable(true)
        }
        catch (e: Exception)
        {
            log.debug("base previous backup {}: {}", dest, e.message)
        }
    }

    private fun findArchive(roots: List<Path>, glob: String): Path?
    {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        for (root in roots)
        {
            if (!Files.isDirectory(root))
            {
                continue
            }
            val found = Files.list(root).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }
                    .findFirst()
                    .orElse(null)
            }
            if (found != null)
            {
                return found
            }
        }
        return null
    }

    private fun isNewer(src: Path, dest: Path): Boolean
    {
        return try
        {
            Files.getLastModifiedTime(src).toMillis() > Files.getLastModifiedTime(dest).toMillis()
        }
        catch (_: Exception)
        {
            true
        }
    }

    private fun copyExec(src: Path, dest: Path): Path
    {
        if (src.toAbsolutePath().normalize() == dest.toAbsolutePath().normalize())
        {
            dest.toFile().setExecutable(true)
            return dest
        }
        return try
        {
            Files.createDirectories(dest.parent)
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
            dest.toFile().setExecutable(true)
            dest
        }
        catch (_: Exception)
        {
            src.toFile().setExecutable(true)
            src
        }
    }

    private fun extractMatching(nodeDir: Path, glob: String)
    {
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
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
        p.waitFor()
    }

    private fun findNamed(root: Path, name: String): Path?
    {
        if (!Files.isDirectory(root))
        {
            return null
        }
        return Files.walk(root, 4).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == name }
                .findFirst()
                .orElse(null)
        }
    }

    private fun linkLocal(src: Path, link: Path)
    {
        try
        {
            Files.createDirectories(link.parent)
            Files.deleteIfExists(link)
            Files.createSymbolicLink(link, src)
        }
        catch (e: Exception)
        {
            log.debug("symlink {} → {}: {}", link, src, e.message)
        }
    }
}
