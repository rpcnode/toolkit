package rpcnode.toolkit.agent.application.client

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import rpcnode.toolkit.agent.infrastructure.node.readNodeClientVersion

/** Layout helpers for safe client update under `{nodeDir}/.toolkit/`. */
object ClientStagingLayout
{
    const val UPDATE_DIR = ".toolkit/client-update"
    const val PREVIOUS_DIR = ".toolkit/client-previous"

    fun updateDir(nodeDir: Path): Path = nodeDir.resolve(UPDATE_DIR)
    fun previousDir(nodeDir: Path): Path = nodeDir.resolve(PREVIOUS_DIR)

    /** Flat artifact names only — never promote `.toolkit` or data leaves. */
    fun isClientArtifactName(name: String): Boolean
    {
        val n = name.trim()
        if (n.isEmpty() || n.contains('/') || n.contains('\\') || n.contains(".."))
        {
            return false
        }
        if (n.startsWith("."))
        {
            return false
        }
        return when (n.lowercase())
        {
            "manifest.json", "install-plan.yml" -> false
            else -> true
        }
    }

    fun listArtifactNames(dir: Path): List<String>
    {
        if (!Files.isDirectory(dir))
        {
            return emptyList()
        }
        return Files.list(dir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .filter { isClientArtifactName(it) }
                .sorted()
                .toList()
        }
    }

    fun clearDir(dir: Path)
    {
        if (!Files.isDirectory(dir))
        {
            return
        }
        Files.list(dir).use { stream ->
            stream.forEach { p ->
                runCatching {
                    if (Files.isDirectory(p))
                    {
                        clearDir(p)
                        Files.deleteIfExists(p)
                    }
                    else
                    {
                        Files.deleteIfExists(p)
                    }
                }
            }
        }
    }

    fun ensureEmptyDir(dir: Path)
    {
        Files.createDirectories(dir)
        clearDir(dir)
    }

    /**
     * Copy live client artifacts listed in [names] (plus VERSION) into previous/.
     * Returns the previous VERSION string when present.
     */
    fun snapshotLiveToPrevious(nodeDir: Path, names: Collection<String>): String
    {
        val previous = previousDir(nodeDir)
        ensureEmptyDir(previous)
        val previousVersion = readNodeClientVersion(nodeDir.toString())
        val toCopy = (names + "VERSION").map { it.trim() }.filter { isClientArtifactName(it) || it == "VERSION" }.toSet()
        for (name in toCopy)
        {
            val src = nodeDir.resolve(name)
            if (!Files.isRegularFile(src))
            {
                continue
            }
            Files.copy(src, previous.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        }
        if (previousVersion.isNotEmpty() && !Files.isRegularFile(previous.resolve("VERSION")))
        {
            Files.writeString(previous.resolve("VERSION"), "$previousVersion\n")
        }
        return previousVersion
    }

    /** Promote staging artifacts into [nodeDir] (overwrite). */
    fun promoteStagingToLive(nodeDir: Path, staging: Path): List<String>
    {
        val names = listArtifactNames(staging)
        val promoted = mutableListOf<String>()
        for (name in names)
        {
            val src = staging.resolve(name)
            val dest = nodeDir.resolve(name)
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
            promoted += name
        }
        val versionSrc = staging.resolve("VERSION")
        if (Files.isRegularFile(versionSrc))
        {
            Files.copy(versionSrc, nodeDir.resolve("VERSION"), StandardCopyOption.REPLACE_EXISTING)
            promoted += "VERSION"
        }
        return promoted
    }

    /** Restore previous/ → live. Returns restored VERSION or empty. */
    fun restorePreviousToLive(nodeDir: Path): String
    {
        val previous = previousDir(nodeDir)
        if (!Files.isDirectory(previous))
        {
            return ""
        }
        val names = listArtifactNames(previous)
        for (name in names)
        {
            val src = previous.resolve(name)
            Files.copy(src, nodeDir.resolve(name), StandardCopyOption.REPLACE_EXISTING)
        }
        val versionSrc = previous.resolve("VERSION")
        if (Files.isRegularFile(versionSrc))
        {
            Files.copy(versionSrc, nodeDir.resolve("VERSION"), StandardCopyOption.REPLACE_EXISTING)
        }
        return readNodeClientVersion(nodeDir.toString())
    }

    fun readPreviousVersion(nodeDir: Path): String
    {
        val fromFile = readNodeClientVersion(previousDir(nodeDir).toString())
        if (fromFile.isNotEmpty())
        {
            return fromFile
        }
        return readNodeClientVersion(nodeDir.toString())
    }
}
