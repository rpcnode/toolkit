package rpcnode.toolkit.chains.cardano.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.zip.ZipInputStream

/**
 * Unpacks cardano-node / cardano-cli and the version-matched `share/<env>/` configs from the release tarball under the
 * node dir, `mithril-client` from its tarball and the optional indexer binaries: `ogmios` (zip) and `cardano-db-sync` with
 * its `schema/` migrations (tarball). Never writes outside the node dir.
 */
object CardanoHostBinaries
{
    const val NODE = "cardano-node"
    const val CLI = "cardano-cli"
    const val MITHRIL = "mithril-client"
    private const val NODE_GLOB = "cardano-node-linux-*.tar.gz"
    private const val MITHRIL_GLOB = "mithril-linux-*.tar.gz"
    const val OGMIOS = "ogmios"
    const val DBSYNC = "cardano-db-sync"
    private const val OGMIOS_GLOB = "ogmios-linux-*.zip"
    private const val DBSYNC_GLOB = "cardano-db-sync-linux-*.tar.gz"
    private val SKIP_CONFIGS = setOf("tracer-config.json", "submit-api-config.json")

    data class Bins(val node: Path, val cli: Path?)

    sealed interface Result
    {
        data class Ok(val bins: Bins) : Result
        data class Failed(val detail: String) : Result
    }

    /** One extracted file, or why there is none. */
    sealed interface Unpacked<out T>
    {
        data class Ok<T>(val value: T) : Unpacked<T>
        data class Failed(val detail: String) : Unpacked<Nothing>
    }

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    fun ensureNode(nodeDir: Path, env: String): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val archive = firstArchive(root, NODE_GLOB)
            ?: return Result.Failed("cardano-node tarball missing under $root — sync clients (IntersectMBO/cardano-node) first")
        val bin = binDir(root).resolve(NODE)
        val upstream = root.resolve(CardanoUnitBodies.CONFIG_DIR).resolve(CardanoUnitBodies.CONFIG_UPSTREAM)
        val current = Files.isExecutable(bin) && Files.isRegularFile(upstream) &&
            Files.getLastModifiedTime(bin) >= Files.getLastModifiedTime(archive)
        if (current)
        {
            return Result.Ok(Bins(bin, binDir(root).resolve(CLI).takeIf { Files.isExecutable(it) }))
        }
        val tmp = Files.createTempDirectory(root, ".cardano-extract-")
        return try
        {
            if (!untar(archive, tmp, listOf("bin/$NODE", "bin/$CLI", "share/$env")))
            {
                return Result.Failed("cardano-node not found after extracting ${archive.fileName}")
            }
            val extractedNode = tmp.resolve("bin").resolve(NODE)
            if (!Files.isRegularFile(extractedNode))
            {
                return Result.Failed("cardano-node not found after extracting ${archive.fileName}")
            }
            val share = tmp.resolve("share").resolve(env)
            if (!Files.isDirectory(share))
            {
                return Result.Failed("share/$env missing in ${archive.fileName}")
            }
            Files.createDirectories(binDir(root))
            install(extractedNode, bin)
            tmp.resolve("bin").resolve(CLI).takeIf { Files.isRegularFile(it) }?.let { install(it, binDir(root).resolve(CLI)) }
            val config = root.resolve(CardanoUnitBodies.CONFIG_DIR)
            Files.createDirectories(config)
            Files.list(share).use { files ->
                files.filter { Files.isRegularFile(it) && it.fileName.toString() !in SKIP_CONFIGS }.forEach { src ->
                    val name = if (src.fileName.toString() == CardanoUnitBodies.CONFIG_JSON) CardanoUnitBodies.CONFIG_UPSTREAM else src.fileName.toString()
                    val dest = config.resolve(name)
                    Files.deleteIfExists(dest)
                    Files.copy(src, dest)
                }
            }
            Result.Ok(Bins(bin, binDir(root).resolve(CLI).takeIf { Files.isExecutable(it) }))
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "cardano-node extract failed")
        }
        finally
        {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    /** `mithril-client` from its tarball, or null when the tarball is missing / unreadable. */
    fun ensureMithril(nodeDir: Path): Path?
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val dest = binDir(root).resolve(MITHRIL)
        val archive = firstArchive(root, MITHRIL_GLOB)
        if (Files.isExecutable(dest) && (archive == null || Files.getLastModifiedTime(dest) >= Files.getLastModifiedTime(archive)))
        {
            return dest
        }
        archive ?: return null
        val tmp = Files.createTempDirectory(root, ".mithril-extract-")
        return try
        {
            if (!untar(archive, tmp, listOf(MITHRIL)) || !Files.isRegularFile(tmp.resolve(MITHRIL)))
            {
                return null
            }
            Files.createDirectories(binDir(root))
            install(tmp.resolve(MITHRIL), dest)
            dest
        }
        catch (_: Exception)
        {
            null
        }
        finally
        {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    /** `bin/ogmios` from the release zip (`bin/ogmios`, completions under `share/`), or a reason when it cannot be had. */
    fun ensureOgmios(nodeDir: Path): Unpacked<Path>
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val dest = binDir(root).resolve(OGMIOS)
        val archive = firstArchive(root, OGMIOS_GLOB)
        if (Files.isExecutable(dest) && (archive == null || Files.getLastModifiedTime(dest) >= Files.getLastModifiedTime(archive)))
        {
            return Unpacked.Ok(dest)
        }
        archive ?: return Unpacked.Failed(
            "Ogmios archive missing under $root — sync clients (CardanoSolutions/ogmios) first, or pick install option indexer=none",
        )
        val tmp = Files.createTempDirectory(root, ".ogmios-extract-")
        return try
        {
            val extracted = tmp.resolve(OGMIOS)
            if (!unzipMember(archive, "bin/$OGMIOS", extracted))
            {
                return Unpacked.Failed("bin/ogmios not found in ${archive.fileName}")
            }
            Files.createDirectories(binDir(root))
            install(extracted, dest)
            Unpacked.Ok(dest)
        }
        catch (e: Exception)
        {
            Unpacked.Failed(e.message ?: "ogmios extract failed")
        }
        finally
        {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    /**
     * `bin/cardano-db-sync` and `schema/` (the SQL migrations db-sync applies itself, `--schema-dir`) from the release tarball.
     * Upstream publishes the Linux build for x86_64 only (static musl) — on another CPU the tarball is not there.
     */
    fun ensureDbSync(nodeDir: Path): Unpacked<Path>
    {
        val root = nodeDir.toAbsolutePath().normalize()
        val dest = binDir(root).resolve(DBSYNC)
        val schema = root.resolve(CardanoIndexerBodies.SCHEMA_DIR)
        val archive = firstArchive(root, DBSYNC_GLOB)
        if (Files.isExecutable(dest) && Files.isDirectory(schema) &&
            (archive == null || Files.getLastModifiedTime(dest) >= Files.getLastModifiedTime(archive)))
        {
            return Unpacked.Ok(dest)
        }
        archive ?: return Unpacked.Failed(
            "cardano-db-sync archive missing under $root — upstream ships Linux x86_64 only; sync clients (IntersectMBO/cardano-db-sync) " +
                "on an x86_64 host, or pick install option indexer=ogmios/none",
        )
        val tmp = Files.createTempDirectory(root, ".dbsync-extract-")
        return try
        {
            if (!untar(archive, tmp, listOf("bin/$DBSYNC", CardanoIndexerBodies.SCHEMA_DIR)))
            {
                return Unpacked.Failed("cardano-db-sync / schema not found after extracting ${archive.fileName}")
            }
            val extractedBin = tmp.resolve("bin").resolve(DBSYNC)
            val extractedSchema = tmp.resolve(CardanoIndexerBodies.SCHEMA_DIR)
            if (!Files.isRegularFile(extractedBin) || !Files.isDirectory(extractedSchema))
            {
                return Unpacked.Failed("cardano-db-sync / schema not found after extracting ${archive.fileName}")
            }
            Files.createDirectories(binDir(root))
            install(extractedBin, dest)
            // the migrations must match the binary: replace the whole directory
            schema.toFile().deleteRecursively()
            Files.move(extractedSchema, schema)
            Unpacked.Ok(dest)
        }
        catch (e: Exception)
        {
            Unpacked.Failed(e.message ?: "cardano-db-sync extract failed")
        }
        finally
        {
            runCatching { tmp.toFile().deleteRecursively() }
        }
    }

    /** Copies one zip member to [dest] (streamed — the Ogmios binary is ~200 MB). */
    private fun unzipMember(archive: Path, member: String, dest: Path): Boolean =
        Files.newInputStream(archive).use { raw ->
            ZipInputStream(raw).use { zip ->
                generateSequence { zip.nextEntry }.firstOrNull { !it.isDirectory && it.name.removePrefix("./") == member }
                    ?.let {
                        Files.copy(zip, dest, StandardCopyOption.REPLACE_EXISTING)
                        true
                    } ?: false
            }
        }

    private fun firstArchive(root: Path, glob: String): Path? =
        Files.newDirectoryStream(root, glob).use { stream ->
            stream.filter { Files.isRegularFile(it) }.sortedBy { it.fileName.toString() }.firstOrNull()
        }

    /** Member names are tried as `./name` first (how the release tarballs store them) and then plain. */
    private fun untar(archive: Path, into: Path, members: List<String>): Boolean
    {
        for (prefix in listOf("./", ""))
        {
            val cmd = listOf("tar", "-xzf", archive.toString(), "-C", into.toString()) + members.map { "$prefix$it" }
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            p.inputStream.readBytes()
            if (p.waitFor() == 0)
            {
                return true
            }
        }
        return false
    }

    private fun install(src: Path, dest: Path)
    {
        val tmp = dest.resolveSibling("${dest.fileName}.tmp")
        Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x")) }
        Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
