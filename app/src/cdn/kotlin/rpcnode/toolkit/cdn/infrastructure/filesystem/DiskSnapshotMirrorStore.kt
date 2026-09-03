package rpcnode.toolkit.cdn.infrastructure.filesystem

import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import rpcnode.toolkit.cdn.application.sync.MirrorEntry
import rpcnode.toolkit.cdn.application.sync.SnapshotMirrorStore
import rpcnode.toolkit.cdn.application.sync.snapshotDateFromVersion
import rpcnode.toolkit.cdn.infrastructure.http.BaseManifestMirror
import rpcnode.toolkit.cdn.infrastructure.http.ResumableHttpDownload

/**
 * On-disk layout matches the panel PreferCdn probe:
 * `snapshots/{network}/{env}/{type}/VERSION` and `…/{filename}`.
 *
 * Public catalogue: `snapshots/index.json`, rebuilt from every `site.json` under a
 * process mutex + exclusive file lock so parallel download workers cannot drop rows.
 */
class DiskSnapshotMirrorStore(
    private val root: Path,
    private val download: ResumableHttpDownload = ResumableHttpDownload(),
    private val clock: () -> Instant = { Instant.now() },
) : SnapshotMirrorStore
{
    private val httpText = java.net.http.HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(30))
        .build()
    private val log = LoggerFactory.getLogger(DiskSnapshotMirrorStore::class.java)

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val indexMutex = Mutex()

    override suspend fun currentVersion(network: String, env: String, type: String): String? =
        withContext(Dispatchers.IO) {
            val file = typeDir(network, env, type).resolve("VERSION")
            if (!Files.isRegularFile(file))
            {
                return@withContext null
            }
            Files.readString(file).trim().ifEmpty { null }
        }

    override suspend fun describe(network: String, env: String, type: String): MirrorEntry? =
        withContext(Dispatchers.IO) {
            readSiteCard(typeDir(network, env, type))
                ?: describeFromManifest(network, env, type)
        }

    override suspend fun publish(
        network: String,
        env: String,
        type: String,
        version: String,
        filename: String,
        sourceUrl: String,
        sizeBytes: Long?,
    ) = withContext(Dispatchers.IO) {
        val dir = typeDir(network, env, type)
        Files.createDirectories(dir)
        val tmp = dir.resolve("$filename.tmp")
        val dest = dir.resolve(filename)
        val progressFile = dir.resolve("progress.json")
        Files.writeString(
            progressFile,
            json.encodeToString(
                ProgressDto(
                    network = network,
                    env = env,
                    type = type,
                    version = version,
                    filename = filename,
                    sizeBytes = sizeBytes,
                    officialUrl = sourceUrl,
                ),
            ) + "\n",
        )
        try
        {
            download.fetch("$network/$env/$type $filename", sourceUrl, tmp, sizeBytes)
            Files.deleteIfExists(dest)
            try
            {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            catch (_: java.nio.file.AtomicMoveNotSupportedException)
            {
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            val finishedAt = DateTimeFormatter.ISO_INSTANT.format(clock())
            val actualSize = sizeBytes ?: runCatching { Files.size(dest) }.getOrNull()
            val path = publicPath(network, env, type, filename)
            Files.writeString(dir.resolve("VERSION"), version + "\n")
            val manifest = ManifestDto(
                network = network,
                env = env,
                type = type,
                version = version,
                filename = filename,
                sizeBytes = actualSize,
                officialUrl = sourceUrl,
                updatedAt = finishedAt,
                path = path,
            )
            Files.writeString(dir.resolve("manifest.json"), json.encodeToString(manifest) + "\n")
            // Per-mirror card — only this directory; concurrent targets do not clash.
            Files.writeString(
                dir.resolve("site.json"),
                json.encodeToString(
                    SiteCardDto(
                        network = network,
                        env = env,
                        type = type,
                        version = version,
                        date = snapshotDateFromVersion(version),
                        sizeBytes = actualSize,
                        filename = filename,
                        path = path,
                        updatedAt = finishedAt,
                    ),
                ) + "\n",
            )
            Files.list(dir).use { stream ->
                stream.filter { Files.isDirectory(it) }.forEach {
                    Files.walk(it).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                }
            }
            val keep = setOf(filename, "VERSION", "manifest.json", "site.json", "downloads.json")
            Files.list(dir).use { stream ->
                stream.filter { Files.isRegularFile(it) }.forEach { f ->
                    if (f.fileName.toString() !in keep)
                    {
                        Files.deleteIfExists(f)
                    }
                }
            }
        }
        finally
        {
            Files.deleteIfExists(progressFile)
        }
        rebuildPublicIndexLocked()
    }

    override suspend fun publishBaseManifest(
        network: String,
        env: String,
        type: String,
        version: String,
        manifestUrl: String,
        sizeBytes: Long?,
        publicOrigin: String,
    ) = withContext(Dispatchers.IO) {
        val dir = typeDir(network, env, type)
        Files.createDirectories(dir)
        val progressFile = dir.resolve("progress.json")
        val origin = publicOrigin.trim().trimEnd('/')
        if (origin.isEmpty())
        {
            error("CDN_PUBLIC_ORIGIN is empty")
        }
        val publicBase = "$origin/snapshots/$network/$env/$type"
        val path = "$network/$env/$type/$version/manifest.json"
        Files.writeString(
            progressFile,
            json.encodeToString(
                ProgressDto(
                    network = network,
                    env = env,
                    type = type,
                    version = version,
                    filename = "manifest.json",
                    sizeBytes = sizeBytes,
                    officialUrl = manifestUrl,
                ),
            ) + "\n",
        )
        try
        {
            val upstreamManifest = fetchText(manifestUrl)
                ?: error("failed to GET Base manifest $manifestUrl")
            val upstreamBase = BaseManifestMirror.upstreamBaseUrl(upstreamManifest)
                ?: error("Base manifest missing base_url")
            val segments = BaseManifestMirror.segmentRelativePaths(upstreamManifest)
            log.info(
                "base manifest {} {} — {} segments under {}",
                "$network/$env/$type",
                version,
                segments.size,
                upstreamBase,
            )
            for ((i, rel) in segments.withIndex())
            {
                val url = BaseManifestMirror.joinUrl(upstreamBase, rel)
                val dest = dir.resolve(rel)
                Files.createDirectories(dest.parent)
                val tmp = dest.resolveSibling(dest.fileName.toString() + ".tmp")
                download.fetch(
                    "$network/$env/$type ${i + 1}/${segments.size} $rel",
                    url,
                    tmp,
                    expectedBytes = null,
                )
                Files.deleteIfExists(dest)
                try
                {
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }
                catch (_: java.nio.file.AtomicMoveNotSupportedException)
                {
                    Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val rewritten = BaseManifestMirror.rewriteBaseUrl(upstreamManifest, publicBase)
            val versionDir = dir.resolve(version)
            Files.createDirectories(versionDir)
            Files.writeString(versionDir.resolve("manifest.json"), rewritten + "\n")
            val finishedAt = DateTimeFormatter.ISO_INSTANT.format(clock())
            Files.writeString(dir.resolve("VERSION"), version + "\n")
            // CDN card metadata (not the Base V2 manifest).
            Files.writeString(
                dir.resolve("cdn-manifest.json"),
                json.encodeToString(
                    ManifestDto(
                        network = network,
                        env = env,
                        type = type,
                        version = version,
                        filename = "manifest.json",
                        sizeBytes = sizeBytes,
                        officialUrl = manifestUrl,
                        updatedAt = finishedAt,
                        path = path,
                    ),
                ) + "\n",
            )
            Files.writeString(
                dir.resolve("site.json"),
                json.encodeToString(
                    SiteCardDto(
                        network = network,
                        env = env,
                        type = type,
                        version = version,
                        date = snapshotDateFromVersion(version),
                        sizeBytes = sizeBytes,
                        filename = "manifest.json",
                        path = path,
                        updatedAt = finishedAt,
                    ),
                ) + "\n",
            )
            pruneStaleBaseVersions(dir, version)
        }
        finally
        {
            Files.deleteIfExists(progressFile)
        }
        rebuildPublicIndexLocked()
    }

    override suspend fun rebuildPublicIndex() = withContext(Dispatchers.IO) {
        rebuildPublicIndexLocked()
    }

    override suspend fun writeIndex(entries: List<MirrorEntry>) = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            withIndexFileLock {
                writeIndexUnlocked(entries)
            }
        }
    }

    override suspend fun listPublished(): List<MirrorEntry> = withContext(Dispatchers.IO) {
        indexMutex.withLock {
            withIndexFileLock {
                val fromDisk = scanSiteCards()
                if (fromDisk.isNotEmpty())
                {
                    return@withIndexFileLock fromDisk
                }
                readIndexFile()
            }
        }
    }

    private suspend fun rebuildPublicIndexLocked() = indexMutex.withLock {
        withIndexFileLock {
            writeIndexUnlocked(scanSiteCards())
        }
    }

    private fun scanSiteCards(): List<MirrorEntry>
    {
        val snapshots = root.resolve("snapshots")
        if (!Files.isDirectory(snapshots))
        {
            return emptyList()
        }
        val out = mutableListOf<MirrorEntry>()
        Files.walk(snapshots).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() == "site.json" }
                .forEach { file ->
                    readSiteCardFile(file)?.let { out += it }
                }
        }
        return out.sortedWith(compareBy({ it.network }, { it.env }, { it.type }))
    }

    private fun readSiteCard(dir: Path): MirrorEntry? =
        readSiteCardFile(dir.resolve("site.json"))

    private fun readSiteCardFile(file: Path): MirrorEntry?
    {
        if (!Files.isRegularFile(file))
        {
            return null
        }
        val card = runCatching { json.decodeFromString<SiteCardDto>(Files.readString(file)) }.getOrNull()
            ?: return null
        val type = card.type.ifBlank { "full" }
        return MirrorEntry(
            network = card.network,
            env = card.env,
            type = type,
            version = card.version,
            date = card.date.ifBlank { snapshotDateFromVersion(card.version) },
            sizeBytes = card.sizeBytes,
            filename = card.filename,
            path = card.path.ifBlank { publicPath(card.network, card.env, type, card.filename) },
            updatedAt = card.updatedAt,
        )
    }

    private fun describeFromManifest(network: String, env: String, type: String): MirrorEntry?
    {
        val versionFile = typeDir(network, env, type).resolve("VERSION")
        if (!Files.isRegularFile(versionFile))
        {
            return null
        }
        val version = Files.readString(versionFile).trim().ifEmpty { return null }
        val manifest = readManifest(typeDir(network, env, type))
        val filename = manifest?.filename.orEmpty()
        return MirrorEntry(
            network = network,
            env = env,
            type = type,
            version = version,
            date = snapshotDateFromVersion(version),
            sizeBytes = manifest?.sizeBytes,
            filename = filename,
            path = publicPath(network, env, type, filename),
            updatedAt = manifest?.updatedAt,
        )
    }

    private fun writeIndexUnlocked(entries: List<MirrorEntry>)
    {
        Files.createDirectories(root.resolve("snapshots"))
        val generatedAt = DateTimeFormatter.ISO_INSTANT.format(clock())
        val dto = IndexDto(
            generatedAt = generatedAt,
            mirrors = entries.map {
                IndexItemDto(
                    network = it.network,
                    env = it.env,
                    type = it.type,
                    version = it.version,
                    date = it.date,
                    sizeBytes = it.sizeBytes,
                    filename = it.filename,
                    path = it.path.ifBlank { publicPath(it.network, it.env, it.type, it.filename) },
                    updatedAt = it.updatedAt,
                )
            },
        )
        val index = root.resolve("snapshots/index.json")
        val tmp = root.resolve("snapshots/index.json.tmp")
        Files.writeString(tmp, json.encodeToString(dto) + "\n")
        try
        {
            Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
        catch (_: java.nio.file.AtomicMoveNotSupportedException)
        {
            Files.move(tmp, index, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readIndexFile(): List<MirrorEntry>
    {
        val index = root.resolve("snapshots/index.json")
        if (!Files.isRegularFile(index))
        {
            return emptyList()
        }
        val dto = json.decodeFromString<IndexDto>(Files.readString(index))
        return dto.mirrors.map {
            val filename = it.filename
            val type = it.type.ifBlank { "full" }
            MirrorEntry(
                network = it.network,
                env = it.env,
                type = type,
                version = it.version,
                date = it.date,
                sizeBytes = it.sizeBytes,
                filename = filename,
                path = it.path.ifBlank { publicPath(it.network, it.env, type, filename) },
                updatedAt = it.updatedAt,
            )
        }
    }

    private fun <T> withIndexFileLock(block: () -> T): T
    {
        val snapshots = root.resolve("snapshots")
        Files.createDirectories(snapshots)
        val lockPath = snapshots.resolve(".index.lock")
        FileChannel.open(
            lockPath,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
        ).use { channel ->
            val lock = channel.lock()
            try
            {
                return block()
            }
            finally
            {
                lock.release()
            }
        }
    }

    private fun typeDir(network: String, env: String, type: String): Path =
        root.resolve("snapshots").resolve(network).resolve(env).resolve(type)

    private fun publicPath(network: String, env: String, type: String, filename: String): String =
        listOf(network, env, type, filename).joinToString("/")

    private fun fetchText(url: String): String?
    {
        return try
        {
            val req = java.net.http.HttpRequest.newBuilder(java.net.URI(url))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("User-Agent", "rpcnode-cdn")
                .GET()
                .build()
            val resp = httpText.send(req, java.net.http.HttpResponse.BodyHandlers.ofString())
            if (resp.statusCode() !in 200 until 300)
            {
                return null
            }
            resp.body()
        }
        catch (_: Exception)
        {
            null
        }
    }

    /**
     * Drop previous Base version dirs (numeric names) and leftover `.tmp` segment files.
     * Keeps `static_files/` and the current [keepVersion] tree.
     */
    private fun pruneStaleBaseVersions(dir: Path, keepVersion: String)
    {
        val keepMeta = setOf(
            "VERSION",
            "site.json",
            "cdn-manifest.json",
            "downloads.json",
            "progress.json",
            "static_files",
            keepVersion,
        )
        Files.list(dir).use { stream ->
            stream.forEach { child ->
                val name = child.fileName.toString()
                if (name in keepMeta || name == "static_files")
                {
                    return@forEach
                }
                if (Files.isDirectory(child) && name.all { it.isDigit() } && name != keepVersion)
                {
                    Files.walk(child).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    return@forEach
                }
                if (Files.isRegularFile(child) && name.endsWith(".tmp"))
                {
                    Files.deleteIfExists(child)
                }
            }
        }
    }

    private fun readManifest(dir: Path): ManifestDto?
    {
        for (name in listOf("cdn-manifest.json", "manifest.json"))
        {
            val file = dir.resolve(name)
            if (!Files.isRegularFile(file))
            {
                continue
            }
            val parsed = runCatching { json.decodeFromString<ManifestDto>(Files.readString(file)) }.getOrNull()
            if (parsed != null)
            {
                return parsed
            }
        }
        return null
    }

    @Serializable
    private data class ProgressDto(
        val network: String,
        val env: String,
        val type: String = "full",
        val version: String,
        val filename: String,
        @SerialName("size_bytes") val sizeBytes: Long? = null,
        @SerialName("official_url") val officialUrl: String,
    )

    @Serializable
    private data class ManifestDto(
        val network: String,
        val env: String,
        val type: String = "full",
        val version: String,
        val filename: String,
        @SerialName("size_bytes") val sizeBytes: Long? = null,
        @SerialName("official_url") val officialUrl: String,
        @SerialName("updated_at") val updatedAt: String? = null,
        val path: String? = null,
    )

    @Serializable
    private data class SiteCardDto(
        val network: String,
        val env: String,
        val type: String,
        val version: String,
        val date: String,
        @SerialName("size_bytes") val sizeBytes: Long? = null,
        val filename: String,
        val path: String,
        @SerialName("updated_at") val updatedAt: String,
    )

    @Serializable
    private data class IndexDto(
        @SerialName("generated_at") val generatedAt: String = "",
        val mirrors: List<IndexItemDto> = emptyList(),
    )

    @Serializable
    private data class IndexItemDto(
        val network: String,
        val env: String,
        val type: String = "full",
        val version: String,
        val date: String = "",
        @SerialName("size_bytes") val sizeBytes: Long? = null,
        val filename: String = "",
        val path: String = "",
        @SerialName("updated_at") val updatedAt: String? = null,
    )
}
