package rpcnode.toolkit.chains.xrpl.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import org.slf4j.LoggerFactory

/**
 * Installs `xrpld` from the Ripple `.deb` under `{nodeDir}/bin/`.
 * Never writes `/opt/ripple` or runs apt on the host.
 */
object XrplHostBinaries
{
    private val log = LoggerFactory.getLogger(XrplHostBinaries::class.java)

    const val BIN = XrplUnitBodies.BIN_NAME
    private const val DEB_NAME = "xrpld-amd64.deb"

    data class Bins(val node: Path)

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
        val nodeDest = dest.resolve(BIN)
        if (ready(nodeDest))
        {
            return Result.Ok(Bins(node = nodeDest))
        }
        return try
        {
            Files.createDirectories(dest)
            val deb = root.resolve(DEB_NAME)
            if (!Files.isRegularFile(deb))
            {
                return Result.Failed(
                    "xrpld .deb missing under $root — sync clients (Ripple xrpld pool) first",
                )
            }
            val extractDir = root.resolve(".toolkit/deb-xrpld")
            if (!extractDeb(deb, extractDir))
            {
                return Result.Failed("dpkg-deb extract failed: $DEB_NAME")
            }
            val found = findNamed(extractDir, BIN)
                ?: return Result.Failed("xrpld binary not found inside $DEB_NAME")
            Files.copy(found, nodeDest, StandardCopyOption.REPLACE_EXISTING)
            makeExecutable(nodeDest)
            if (!ready(nodeDest))
            {
                return Result.Failed("xrpld not executable after extracting $deb")
            }
            Result.Ok(Bins(node = nodeDest))
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "xrpld binary install failed")
        }
    }

    private fun ready(path: Path): Boolean =
        Files.isRegularFile(path) && Files.isExecutable(path)

    private fun extractDeb(deb: Path, dest: Path): Boolean
    {
        return try
        {
            if (Files.isDirectory(dest))
            {
                Files.walk(dest).use { stream ->
                    stream.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            Files.createDirectories(dest)
            val pb = ProcessBuilder(
                "dpkg-deb",
                "-x",
                deb.toAbsolutePath().toString(),
                dest.toAbsolutePath().toString(),
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().take(400)
            val code = p.waitFor()
            if (code != 0)
            {
                log.warn("dpkg-deb {}: {}", code, out)
            }
            code == 0
        }
        catch (e: Exception)
        {
            log.warn("dpkg-deb: {}", e.message)
            false
        }
    }

    private fun findNamed(root: Path, name: String): Path?
    {
        if (!Files.isDirectory(root))
        {
            return null
        }
        Files.walk(root, 6).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == name }
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
