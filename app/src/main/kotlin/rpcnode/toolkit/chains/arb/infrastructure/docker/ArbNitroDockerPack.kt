package rpcnode.toolkit.chains.arb.infrastructure.docker

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

/**
 * Builds the Nitro client tarball from Docker Hub image layers over HTTPS
 * (Registry V2) — no Docker daemon / `docker pull`.
 *
 * Layout: `bin/nitro`, `nitro-legacy/machines/`, `target/machines/`.
 * Host agent unpacks via [rpcnode.toolkit.chains.arb.infrastructure.ArbHostBinaries].
 */
object ArbNitroDockerPack
{
    private val WANTED_PREFIXES = listOf(
        "usr/local/bin/nitro",
        "home/user/nitro-legacy/machines",
        "home/user/target/machines",
    )

    /**
     * @param imageRef e.g. `offchainlabs/nitro-node:v3.11.3-beb2108` (no `docker://` prefix)
     */
    fun pack(
        imageRef: String,
        destTarGz: Path,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> },
        registry: ArbNitroRegistryClient = ArbNitroRegistryClient(),
    )
    {
        val image = imageRef.trim().removePrefix("docker://").trim()
        require(image.isNotEmpty()) { "empty nitro docker image" }
        val slash = image.indexOf('/')
        val colon = image.lastIndexOf(':')
        require(slash > 0 && colon > slash) {
            "expected repository:tag (got $image)"
        }
        val repository = image.substring(0, colon)
        val tag = image.substring(colon + 1)
        val arch = hostArch()
        onProgress(0L, -1L)
        val layers = registry.resolveLayers(repository, tag, arch)
        val total = layers.sumOf { it.size }.coerceAtLeast(1L)
        val stage = Files.createTempDirectory("nitro-registry-")
        val layerFile = Files.createTempFile("nitro-layer-", ".tar.gz")
        try
        {
            var downloaded = 0L
            for (layer in layers)
            {
                Files.deleteIfExists(layerFile)
                registry.downloadBlob(repository, layer.digest, layerFile)
                applyLayer(layerFile, stage)
                downloaded += layer.size
                onProgress(downloaded, total)
            }

            normalizeLayout(stage)
            val nitro = stage.resolve("bin").resolve("nitro")
            if (!nitro.isRegularFile())
            {
                throw IllegalStateException(
                    "nitro missing after registry extract of $image (linux/$arch); " +
                        "stage=${stageSnapshot(stage)}",
                )
            }
            nitro.toFile().setExecutable(true)
            val legacy = stage.resolve("nitro-legacy").resolve("machines")
            val target = stage.resolve("target").resolve("machines")
            val legacyOk = Files.isDirectory(legacy) && hasMachineContent(legacy)
            val targetOk = Files.isDirectory(target) && hasMachineContent(target)
            if (!legacyOk || !targetOk)
            {
                throw IllegalStateException(
                    "wasm machine roots missing after registry extract of $image " +
                        "(need nitro-legacy/machines and target/machines); " +
                        "legacyOk=$legacyOk targetOk=$targetOk stage=${stageSnapshot(stage)}",
                )
            }

            Files.createDirectories(destTarGz.parent)
            val tmpTar = destTarGz.resolveSibling(destTarGz.fileName.toString() + ".partial")
            Files.deleteIfExists(tmpTar)
            val tar = ProcessBuilder(
                "tar", "-czf", tmpTar.toAbsolutePath().toString(),
                "-C", stage.toAbsolutePath().toString(),
                "bin", "nitro-legacy", "target",
            )
            tar.redirectErrorStream(true)
            val tp = tar.start()
            val tarOut = tp.inputStream.bufferedReader().readText()
            val tarCode = tp.waitFor()
            if (tarCode != 0)
            {
                throw IllegalStateException("tar pack nitro failed: $tarOut")
            }
            Files.move(tmpTar, destTarGz, StandardCopyOption.REPLACE_EXISTING)
            onProgress(Files.size(destTarGz), Files.size(destTarGz))
        }
        finally
        {
            forceDelete(layerFile)
            forceDelete(stage)
        }
    }

    /** List + selective extract + whiteouts for one gzip layer. */
    internal fun applyLayer(layerTarGz: Path, stage: Path)
    {
        val entries = listTar(layerTarGz)
        if (entries.isEmpty())
        {
            return
        }
        val whiteouts = entries.filter { isWhiteoutEntry(it) && isUnderWanted(it) }
        // Overlay semantics: whiteouts apply before new files in the same layer.
        for (wh in whiteouts)
        {
            applyWhiteout(stage, normalizeEntry(wh))
        }
        // GNU tar -T breaks if the list includes directory members (`…/machines/`).
        // Pass file entries only; parents are created automatically.
        val members = entries.filter { entry ->
            val trimmed = entry.trim()
            !trimmed.endsWith('/') && isWantedMember(trimmed)
        }
        if (members.isNotEmpty())
        {
            val listFile = Files.createTempFile("nitro-tar-members-", ".txt")
            try
            {
                Files.write(listFile, members)
                val cmd = listOf(
                    "tar", "-xzf", layerTarGz.toAbsolutePath().toString(),
                    "-C", stage.toAbsolutePath().toString(),
                    "--no-same-owner",
                    "--no-same-permissions",
                    "--verbatim-files-from",
                    "-T", listFile.toAbsolutePath().toString(),
                )
                val pb = ProcessBuilder(cmd)
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText()
                val code = p.waitFor()
                if (code != 0)
                {
                    throw IllegalStateException("tar extract layer failed: $out")
                }
            }
            finally
            {
                Files.deleteIfExists(listFile)
            }
        }
    }

    internal fun normalizeLayout(stage: Path)
    {
        val nestedNitro = stage.resolve("usr/local/bin/nitro")
        val binNitro = stage.resolve("bin/nitro")
        if (nestedNitro.isRegularFile())
        {
            Files.createDirectories(binNitro.parent)
            if (!binNitro.isRegularFile() ||
                nestedNitro.toAbsolutePath().normalize() != binNitro.toAbsolutePath().normalize()
            ) {
                Files.copy(nestedNitro, binNitro, StandardCopyOption.REPLACE_EXISTING)
            }
            binNitro.toFile().setExecutable(true)
        }
        relocateTree(stage.resolve("home/user/nitro-legacy/machines"), stage.resolve("nitro-legacy/machines"))
        relocateTree(stage.resolve("home/user/target/machines"), stage.resolve("target/machines"))
    }

    /**
     * Moves [from] → [to]. Image trees may be mode 555; chmod before rename/copy.
     * If [to] already exists, merges then deletes [from].
     */
    private fun relocateTree(from: Path, to: Path)
    {
        if (!Files.isDirectory(from))
        {
            return
        }
        ensureWritableTree(from)
        if (Files.isDirectory(to))
        {
            ensureWritableTree(to)
            copyTree(from, to)
            forceDelete(from)
            return
        }
        Files.createDirectories(to.parent)
        try
        {
            Files.move(from, to)
        }
        catch (_: Exception)
        {
            copyTree(from, to)
            forceDelete(from)
        }
    }

    private fun copyTree(from: Path, to: Path)
    {
        Files.walk(from).use { stream ->
            stream.forEach { src ->
                val rel = from.relativize(src)
                val dest = to.resolve(rel.toString())
                when
                {
                    Files.isSymbolicLink(src) ->
                    {
                        Files.createDirectories(dest.parent)
                        Files.copy(
                            src,
                            dest,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                    }
                    Files.isDirectory(src) -> Files.createDirectories(dest)
                    else ->
                    {
                        Files.createDirectories(dest.parent)
                        Files.copy(
                            src,
                            dest,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES,
                        )
                    }
                }
            }
        }
    }

    private fun hasMachineContent(dir: Path): Boolean
    {
        if (!Files.isDirectory(dir))
        {
            return false
        }
        Files.list(dir).use { stream ->
            return stream.findAny().isPresent
        }
    }

    private fun stageSnapshot(stage: Path): String
    {
        fun exists(p: String): String
        {
            val path = stage.resolve(p)
            return when
            {
                Files.isSymbolicLink(path) -> "$p=symlink"
                Files.isDirectory(path) -> "$p=dir"
                Files.isRegularFile(path) -> "$p=file"
                else -> "$p=missing"
            }
        }
        return listOf(
            exists("bin/nitro"),
            exists("usr/local/bin/nitro"),
            exists("home/user/nitro-legacy/machines"),
            exists("home/user/target/machines"),
            exists("nitro-legacy/machines"),
            exists("target/machines"),
        ).joinToString(",")
    }

    private fun ensureWritableTree(root: Path)
    {
        if (!Files.exists(root))
        {
            return
        }
        val pb = ProcessBuilder("chmod", "-R", "u+w", root.toAbsolutePath().toString())
        pb.redirectErrorStream(true)
        val p = pb.start()
        p.inputStream.bufferedReader().readText()
        p.waitFor()
    }

    private fun forceDelete(path: Path)
    {
        if (!Files.exists(path))
        {
            return
        }
        ensureWritableTree(path)
        path.toFile().deleteRecursively()
    }

    private fun listTar(layerTarGz: Path): List<String>
    {
        val pb = ProcessBuilder("tar", "-tzf", layerTarGz.toAbsolutePath().toString())
        pb.redirectErrorStream(true)
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        val code = p.waitFor()
        if (code != 0)
        {
            if (Files.size(layerTarGz) < 64)
            {
                return emptyList()
            }
            throw IllegalStateException("tar list layer failed: $out")
        }
        return out.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
    }

    private fun applyWhiteout(stage: Path, entry: String)
    {
        val path = Path.of(entry)
        val name = path.fileName?.toString() ?: return
        val parent = stage.resolve(path.parent ?: Path.of("."))
        if (name == ".wh..wh..opq")
        {
            if (Files.isDirectory(parent))
            {
                ensureWritableTree(parent)
                Files.list(parent).use { stream ->
                    stream.forEach { child -> forceDelete(child) }
                }
            }
            return
        }
        if (!name.startsWith(".wh."))
        {
            return
        }
        val target = parent.resolve(name.removePrefix(".wh."))
        if (Files.exists(target))
        {
            forceDelete(target)
        }
    }

    private fun isWantedMember(entry: String): Boolean
    {
        val n = normalizeEntry(entry)
        if (n.substringAfterLast('/').startsWith(".wh."))
        {
            return false
        }
        return WANTED_PREFIXES.any { prefix ->
            n == prefix || n.startsWith("$prefix/")
        }
    }

    private fun isUnderWanted(entry: String): Boolean
    {
        val n = normalizeEntry(entry)
        return WANTED_PREFIXES.any { prefix ->
            val dir = prefix.substringBeforeLast('/', missingDelimiterValue = "")
            dir.isNotEmpty() && (n == dir || n.startsWith("$dir/") || n.startsWith("$prefix/"))
        } || WANTED_PREFIXES.any { n == it || n.startsWith("$it/") }
    }

    private fun isWhiteoutEntry(entry: String): Boolean
    {
        val name = normalizeEntry(entry).substringAfterLast('/')
        return name.startsWith(".wh.")
    }

    private fun normalizeEntry(entry: String): String =
        entry.trim().removePrefix("./").removePrefix("/")

    private fun hostArch(): String
    {
        val arch = System.getProperty("os.arch")?.lowercase().orEmpty()
        return when
        {
            arch.contains("aarch64") || arch.contains("arm64") -> "arm64"
            else -> "amd64"
        }
    }
}
