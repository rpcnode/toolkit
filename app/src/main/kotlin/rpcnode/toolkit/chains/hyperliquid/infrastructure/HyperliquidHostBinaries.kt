package rpcnode.toolkit.chains.hyperliquid.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Installs `hl-visor` under `{nodeDir}/bin/` from the synced bare binary.
 * Never writes `/opt/hyperliquid`.
 */
object HyperliquidHostBinaries
{
    const val BINARY = HyperliquidUnitBodies.BINARY

    data class Bins(val visor: Path)

    sealed interface Result
    {
        data class Ok(val bins: Bins) : Result
        data class Failed(val detail: String) : Result
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    fun ensure(nodeDir: Path): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val dest = binDir(root).resolve(BINARY)
        if (ready(dest))
        {
            return Result.Ok(Bins(visor = dest))
        }
        return try
        {
            Files.createDirectories(dest.parent)
            val src = findBinary(root)
                ?: return Result.Failed(
                    "hl-visor missing under $root — sync Hyperliquid clients (CDN hl-visor) first",
                )
            if (src != dest)
            {
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            makeExecutable(dest)
            if (!ready(dest))
            {
                return Result.Failed("hl-visor not executable after install at $dest")
            }
            Result.Ok(Bins(visor = dest))
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "hl-visor install failed")
        }
    }

    private fun findBinary(root: Path): Path?
    {
        val candidates = listOf(
            root.resolve("bin").resolve(BINARY),
            root.resolve(BINARY),
        )
        candidates.firstOrNull { Files.isRegularFile(it) && Files.size(it) > 0 }?.let { return it }
        if (!Files.isDirectory(root))
        {
            return null
        }
        return Files.list(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString() == BINARY }
                .findFirst()
                .orElse(null)
        }
    }

    private fun ready(path: Path): Boolean =
        Files.isRegularFile(path) && Files.isExecutable(path) && Files.size(path) > 0

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
