package rpcnode.toolkit.chains.solana.infrastructure.proc

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Resolve / install Agave binaries **under [nodeDir] only**.
 *
 * Kotlin host layout never writes `/opt/<chain>` or `/etc/<chain>` (unlike the Go sibling).
 * Anza release tarballs still ship CLI tools but **stopped including `agave-validator`
 * from Agave v3.0** — Start extracts tools from the synced archive, then cargo-builds the
 * validator into `{nodeDir}/bin` from the VERSION pin when needed.
 */
object SolanaAgaveBins
{
    const val VALIDATOR = "agave-validator"
    const val KEYGEN = "solana-keygen"
    const val ARCHIVE_GLOB = "solana-release-*.tar.bz2"

    fun binDir(nodeDir: Path): Path = nodeDir.resolve("bin")

    fun validatorPath(nodeDir: Path): Path = binDir(nodeDir).resolve(VALIDATOR)

    fun keygenPath(nodeDir: Path): Path = binDir(nodeDir).resolve(KEYGEN)

    fun nodeDirCandidates(nodeDir: Path, name: String): List<Path> = listOf(
        nodeDir.resolve(name),
        binDir(nodeDir).resolve(name),
        nodeDir.resolve("solana-release/bin").resolve(name),
    )

    fun firstExisting(candidates: List<Path>): Path? =
        candidates.firstOrNull { Files.isRegularFile(it) }

    /**
     * Ensure [VALIDATOR] exists under [nodeDir]/bin, extracting the synced tarball and/or
     * building from source when Anza's archive has no validator binary.
     */
    fun ensureValidator(nodeDir: Path): EnsureResult
    {
        firstExisting(nodeDirCandidates(nodeDir, VALIDATOR))?.let { found ->
            return installIntoBin(found, validatorPath(nodeDir))
        }
        extractArchive(nodeDir)
        installToolsFromTree(nodeDir)
        firstExisting(nodeDirCandidates(nodeDir, VALIDATOR))?.let { found ->
            return installIntoBin(found, validatorPath(nodeDir))
        }
        return when (val built = buildValidatorFromSource(nodeDir))
        {
            is EnsureResult.Ok -> built
            is EnsureResult.Pending -> built
            is EnsureResult.Failed -> EnsureResult.Failed(
                "agave-validator missing under $nodeDir " +
                    "(Anza stopped shipping it in solana-release tarballs since Agave v3.0). " +
                    built.detail,
            )
        }
    }

    fun ensureKeygen(nodeDir: Path): Path?
    {
        firstExisting(nodeDirCandidates(nodeDir, KEYGEN))?.let { found ->
            return when (val linked = installIntoBin(found, keygenPath(nodeDir)))
            {
                is EnsureResult.Ok -> linked.path
                is EnsureResult.Failed, is EnsureResult.Pending -> found
            }
        }
        extractArchive(nodeDir)
        installToolsFromTree(nodeDir)
        val found = firstExisting(nodeDirCandidates(nodeDir, KEYGEN)) ?: return null
        return when (val linked = installIntoBin(found, keygenPath(nodeDir)))
        {
            is EnsureResult.Ok -> linked.path
            is EnsureResult.Failed, is EnsureResult.Pending -> found
        }
    }

    fun readPinnedVersion(nodeDir: Path): String?
    {
        val file = nodeDir.resolve("VERSION")
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching {
            Files.readString(file).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
        }.getOrNull()
    }

    fun releaseTag(version: String): String
    {
        val v = version.trim().removePrefix("v")
        return "v$v"
    }

    /** Cargo/work tree kept outside node_dir (survives client sync wipe). */
    internal fun workDirForVersion(version: String): Path =
        Path.of("/var/tmp", "rpcnode-agave-src-${version.trim().removePrefix("v")}")

    sealed interface EnsureResult
    {
        data class Ok(val path: Path) : EnsureResult
        data class Failed(val detail: String) : EnsureResult
        /** Background cargo/OS build in progress — systemd Start comes on a later attempt. */
        data class Pending(val detail: String) : EnsureResult
    }

    private fun installIntoBin(src: Path, dest: Path): EnsureResult
    {
        return try
        {
            Files.createDirectories(dest.parent)
            if (src.toAbsolutePath().normalize() == dest.toAbsolutePath().normalize())
            {
                makeExecutable(dest)
                return EnsureResult.Ok(dest)
            }
            if (Files.isSymbolicLink(dest) || Files.isRegularFile(dest))
            {
                Files.deleteIfExists(dest)
            }
            Files.createSymbolicLink(dest, src.toAbsolutePath().normalize())
            makeExecutable(dest)
            EnsureResult.Ok(dest)
        }
        catch (e: Exception)
        {
            try
            {
                Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
                makeExecutable(dest)
                EnsureResult.Ok(dest)
            }
            catch (copy: Exception)
            {
                EnsureResult.Failed(copy.message ?: e.message ?: "install $dest failed")
            }
        }
    }

    private fun installToolsFromTree(nodeDir: Path)
    {
        val binDest = binDir(nodeDir)
        try
        {
            Files.createDirectories(binDest)
        }
        catch (_: Exception)
        {
            return
        }
        for (name in listOf(VALIDATOR, KEYGEN, "solana", "agave-ledger-tool", "solana-test-validator"))
        {
            val found = firstExisting(nodeDirCandidates(nodeDir, name))
                ?: findNamed(nodeDir, name)
                ?: continue
            val dest = binDest.resolve(name)
            if (Files.isRegularFile(dest) || Files.isSymbolicLink(dest))
            {
                continue
            }
            runCatching { installIntoBin(found, dest) }
        }
    }

    internal fun extractArchive(nodeDir: Path)
    {
        if (!Files.isDirectory(nodeDir))
        {
            return
        }
        if (Files.isRegularFile(nodeDir.resolve("solana-release/bin").resolve(KEYGEN)) ||
            Files.isRegularFile(nodeDir.resolve("solana-release/bin").resolve(VALIDATOR))
        )
        {
            return
        }
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$ARCHIVE_GLOB")
        val archive = runCatching {
            Files.list(nodeDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && matcher.matches(it.fileName) }
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull() ?: return
        val name = archive.fileName.toString()
        val flags = when
        {
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> listOf("-xjf", name)
            name.endsWith(".tar.xz") -> listOf("-xJf", name)
            else -> listOf("-xzf", name)
        }
        val pb = ProcessBuilder(listOf("tar") + flags)
        pb.directory(nodeDir.toFile())
        pb.redirectErrorStream(true)
        runCatching {
            val p = pb.start()
            p.inputStream.bufferedReader().readText()
            p.waitFor()
        }
    }

    private fun buildValidatorFromSource(nodeDir: Path): EnsureResult
    {
        val version = readPinnedVersion(nodeDir)
            ?: return EnsureResult.Failed(
                "No VERSION pin under $nodeDir — Install latest Agave in the panel, sync, then retry Start " +
                    "(host will install OS deps + rustup and cargo-build agave-validator under node_dir/bin).",
            )
        val tag = releaseTag(version)
        val dest = validatorPath(nodeDir)
        if (Files.isRegularFile(dest) || (Files.isSymbolicLink(dest) && Files.exists(dest)))
        {
            makeExecutable(dest)
            return EnsureResult.Ok(dest)
        }
        // Build tree outside node_dir so wipe/re-sync does not delete a multi-hour cargo cache.
        val work = workDirForVersion(version)
        // Sync only refreshes the Anza tarball — it wipes {nodeDir}/bin/agave-validator and
        // run-validator.sh. Reinstall from a prior /var/tmp build synchronously (no Pending).
        for (cand in listOf(
            work.resolve("bin").resolve(VALIDATOR),
            work.resolve("target/release").resolve(VALIDATOR),
        ))
        {
            if (Files.isRegularFile(cand))
            {
                return installIntoBin(cand, dest)
            }
        }
        val toolkit = nodeDir.resolve(".toolkit")
        val statusFile = toolkit.resolve("agave-build.status")
        val pidFile = toolkit.resolve("agave-build.pid")
        val logFile = toolkit.resolve("agave-build.log")
        val scriptPath = toolkit.resolve("build-agave-validator.sh")

        readBuildPid(pidFile)?.let { pid ->
            if (processAlive(pid))
            {
                return EnsureResult.Pending(
                    "Building agave-validator $tag (pid $pid). " +
                        "Log: $logFile — when ready, press Start again for systemd.",
                )
            }
        }
        if (Files.isRegularFile(dest) || (Files.isSymbolicLink(dest) && Files.exists(dest)))
        {
            makeExecutable(dest)
            return EnsureResult.Ok(dest)
        }
        val lastStatus = readStatus(statusFile)
        if (lastStatus == "failed")
        {
            // Previous attempt finished with error — start a new background build below.
            Files.writeString(statusFile, "retrying\n")
        }

        return try
        {
            Files.createDirectories(dest.parent)
            Files.createDirectories(toolkit)
            val script = HostSystemdUnitTemplate.render(
                loadBuildAgaveTemplate(nodeDir),
                mapOf(
                    "dest" to shellSingleQuote(dest.toAbsolutePath().normalize().toString()),
                    "work" to shellSingleQuote(work.toAbsolutePath().normalize().toString()),
                    "tag" to shellSingleQuote(tag),
                ),
            )
            Files.writeString(scriptPath, script)
            makeExecutable(scriptPath)
            // Detached build: must not block /api/v1/node/start — panel/proxy timeouts cancel the
            // Ktor call and would kill a foreground cargo build before systemd is installed.
            val pb = ProcessBuilder("bash", scriptPath.toAbsolutePath().toString())
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
            pb.redirectErrorStream(true)
            pb.directory(nodeDir.toFile())
            val p = pb.start()
            val pid = p.pid()
            Files.writeString(pidFile, "$pid\n")
            Files.writeString(statusFile, "running\n")
            Thread(
                {
                    try
                    {
                        val code = p.waitFor()
                        if (code == 0 && Files.isRegularFile(dest))
                        {
                            makeExecutable(dest)
                            Files.writeString(statusFile, "ok\n")
                        }
                        else
                        {
                            Files.writeString(statusFile, "failed\n")
                        }
                    }
                    catch (_: Exception)
                    {
                        runCatching { Files.writeString(statusFile, "failed\n") }
                    }
                },
                "agave-build-watch-$pid",
            ).apply {
                isDaemon = true
                start()
            }
            EnsureResult.Pending(
                "Started agave-validator $tag build (pid $pid). " +
                    "Often tens of minutes. Log: $logFile — when ready, press Start again for systemd.",
            )
        }
        catch (e: Exception)
        {
            runCatching { Files.writeString(statusFile, "failed\n") }
            EnsureResult.Failed(e.message ?: "source build failed")
        }
    }

    private fun readBuildPid(pidFile: Path): Long?
    {
        if (!Files.isRegularFile(pidFile))
        {
            return null
        }
        return runCatching {
            Files.readString(pidFile).lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.toLongOrNull()
        }.getOrNull()
    }

    private fun readStatus(statusFile: Path): String =
        runCatching {
            Files.readString(statusFile).lineSequence().map { it.trim().lowercase() }.firstOrNull { it.isNotEmpty() }
        }.getOrNull().orEmpty()

    private fun processAlive(pid: Long): Boolean =
        try
        {
            ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
        }
        catch (_: Exception)
        {
            false
        }

    private fun loadBuildAgaveTemplate(nodeDir: Path): String
    {
        val shipped = nodeDir.resolve("build-agave.sh.tmpl")
        if (Files.isRegularFile(shipped))
        {
            return Files.readString(shipped).trimEnd() + "\n"
        }
        return HostSystemdUnitTemplate.load("solana", "scripts/build-agave.sh.tmpl")
    }

    private fun findNamed(root: Path, name: String): Path?
    {
        if (!Files.isDirectory(root))
        {
            return null
        }
        return runCatching {
            Files.walk(root, 5).use { stream ->
                stream
                    .filter { Files.isRegularFile(it) && it.fileName.toString() == name }
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull()
    }

    private fun makeExecutable(path: Path)
    {
        try
        {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms += PosixFilePermission.OWNER_EXECUTE
            perms += PosixFilePermission.GROUP_EXECUTE
            perms += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(path, perms)
        }
        catch (_: Exception)
        {
            path.toFile().setExecutable(true)
        }
    }

    private fun shellSingleQuote(s: String): String =
        "'" + s.replace("'", "'\\''") + "'"
}
