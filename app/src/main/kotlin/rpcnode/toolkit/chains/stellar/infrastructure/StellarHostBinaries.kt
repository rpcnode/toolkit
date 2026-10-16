package rpcnode.toolkit.chains.stellar.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Unpacks `stellar-rpc` and `stellar-core` from the two SDF `.deb` files in the node dir (`dpkg-deb -x`, no apt source
 * and no root) into `{nodeDir}/bin/`. amd64 only: SDF publishes no arm64 package.
 */
object StellarHostBinaries
{
    const val RPC_DEB = "stellar-rpc-amd64.deb"
    const val CORE_DEB = "stellar-core-noble-amd64.deb"
    private const val MARKER = ".toolkit/stellar-debs.sha256"
    private val PROTOCOL = Regex("(?m)^ledger protocol version:\\s*(\\d+)\\s*$")

    sealed interface Result
    {
        data class Ok(val rpc: Path, val core: Path, val coreProtocol: Int?) : Result
        data class Failed(val detail: String) : Result
    }

    fun parseProtocol(versionOutput: String): Int? =
        PROTOCOL.find(versionOutput)?.groupValues?.get(1)?.toIntOrNull()

    fun ensure(nodeDir: Path, arch: String = System.getProperty("os.arch").orEmpty()): Result
    {
        val root = nodeDir.toAbsolutePath().normalize()
        if (arch.lowercase() !in setOf("amd64", "x86_64"))
        {
            return Result.Failed("Stellar has no arm64 build — SDF publishes amd64 packages only")
        }
        val rpcDeb = root.resolve(RPC_DEB)
        val coreDeb = root.resolve(CORE_DEB)
        if (!Files.isRegularFile(rpcDeb) || !Files.isRegularFile(coreDeb))
        {
            return Result.Failed("stellar .deb files missing under $root — sync clients (stellar-rpc) first")
        }
        val bin = root.resolve("bin")
        val rpc = bin.resolve("stellar-rpc")
        val core = bin.resolve("stellar-core")
        val marker = "rpc=${sha256(rpcDeb)}\ncore=${sha256(coreDeb)}"
        val markerFile = root.resolve(MARKER)
        val current = Files.isExecutable(rpc) && Files.isExecutable(core) &&
            runCatching { Files.readString(markerFile).trim() }.getOrNull() == marker
        if (!current)
        {
            try
            {
                Files.createDirectories(bin)
                install(rpcDeb, root.resolve(".toolkit/deb-stellar-rpc"), "stellar-rpc", rpc)
                install(coreDeb, root.resolve(".toolkit/deb-stellar-core"), "stellar-core", core)
                Files.createDirectories(markerFile.parent)
                Files.writeString(markerFile, "$marker\n")
            }
            catch (e: Exception)
            {
                return Result.Failed(e.message ?: "stellar .deb extract failed")
            }
        }
        val version = runVersion(core)
        if (version.contains("error while loading shared libraries"))
        {
            val lib = version.substringAfter("loading shared libraries:").substringBefore(':').trim()
            return Result.Failed(
                "stellar-core cannot start: $lib — run the Host deps step " +
                    "(libc++1-20 libc++abi1-20 libunwind-20 libpq5 libsqlite3-0), Ubuntu 24.04 required",
            )
        }
        return Result.Ok(rpc, core, parseProtocol(version))
    }

    /** First regular file named [name] under [root] (depth <= 6). */
    fun findBinary(root: Path, name: String): Path?
    {
        if (!Files.isDirectory(root))
        {
            return null
        }
        return Files.walk(root, 6).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString() == name }.findFirst().orElse(null)
        }
    }

    private fun install(deb: Path, dest: Path, name: String, target: Path)
    {
        Files.createDirectories(dest)
        val pb = ProcessBuilder("dpkg-deb", "-x", deb.toString(), dest.toString()).redirectErrorStream(true)
        val process = try
        {
            pb.start()
        }
        catch (e: java.io.IOException)
        {
            throw IllegalStateException("dpkg-deb not found (Debian/Ubuntu host required)")
        }
        val out = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0)
        {
            throw IllegalStateException("dpkg-deb -x ${deb.fileName} failed: ${out.take(200)}")
        }
        val found = findBinary(dest, name) ?: throw IllegalStateException("$name not found in ${deb.fileName}")
        val tmp = target.resolveSibling("${target.fileName}.tmp")
        Files.copy(found, tmp, StandardCopyOption.REPLACE_EXISTING)
        runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rwxr-xr-x")) }
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun runVersion(core: Path): String =
        runCatching {
            val p = ProcessBuilder(core.toString(), "version").redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(10, TimeUnit.SECONDS))
            {
                p.destroyForcibly()
            }
            text
        }.getOrDefault("")

    private fun sha256(file: Path): String
    {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true)
            {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
