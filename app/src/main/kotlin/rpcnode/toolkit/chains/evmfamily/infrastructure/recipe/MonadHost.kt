package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * What a Monad node needs from the host besides its three units: enough CPUs, a safe TrieDB block device, the node identity,
 * the signed name record, kernel buffers. Every function returns the operator-facing failure text (null = fine) so
 * `MonadProfile` can stop the Start before any unit is written. Nothing here partitions or formats a drive.
 */
object MonadHost
{
    /** The official units pin execution to CPUs 1-7, consensus to 8-11 and RPC to 12-15; `monad-cli` import uses CPU 15. */
    const val MIN_CPUS = 16
    const val SECTOR_BYTES = 512L
    const val MIN_DEVICE_BYTES = 100L shl 30
    const val DEVICE_MARKER = ".toolkit/monad-triedb.device"
    const val ENV_FILE = ".toolkit/monad.env"
    const val BACKUP_DIR = ".toolkit/backup"
    const val SYSCTL_FILE = "/etc/sysctl.d/90-monad-network-buffer.conf"
    private val IPV4 = Regex("""(25[0-5]|2[0-4]\d|1?\d?\d)(\.(25[0-5]|2[0-4]\d|1?\d?\d)){3}""")
    private val HEX64 = Regex("[0-9a-f]{64}")

    /** Logical CPUs of the host (`/sys/devices/system/cpu/cpuN`), 0 when unreadable. */
    fun cpuCount(sysCpu: Path = Path.of("/sys/devices/system/cpu")): Int =
        runCatching {
            Files.list(sysCpu).use { s -> s.filter { it.fileName.toString().matches(Regex("cpu\\d+")) }.count().toInt() }
        }.getOrDefault(0)

    fun checkCpus(count: Int = cpuCount()): String? =
        if (count >= MIN_CPUS) null
        else "Monad needs at least $MIN_CPUS logical CPUs (found $count): the units pin execution to CPUs 1-7, consensus to 8-11 and RPC to 12-15 like the official ones"

    /** `/dev/...` only, no spaces / `..` / control characters: the path is written into units and shell scripts. */
    fun validDevicePath(raw: String): Boolean =
        raw.startsWith("/dev/") && !raw.contains("..") && raw.matches(Regex("""/dev/[A-Za-z0-9._:\-/]+"""))

    /**
     * The TrieDB device is only ever touched when it is a block device that nothing uses. [toolkitDir]'s marker file names
     * the device a previous restore of this node initialised (it is then ours and may be re-initialised); any other device
     * must carry no filesystem / RAID / LVM / partition-table signature, which `blkid -p` reports.
     * [initialised] true (Start): the marker must exist — the Snapshot step has not run otherwise.
     */
    fun checkDevice(device: String, root: Path, initialised: Boolean): String?
    {
        if (device.isBlank())
        {
            return "Set the TrieDB block device (install option 'TrieDB block device', e.g. /dev/disk/by-partuuid/…): a raw NVMe partition of ~2 TB"
        }
        if (!validDevicePath(device)) return "TrieDB device '$device' is not a plain /dev path"
        if (!run("test", "-b", device).ok) return "TrieDB device $device is not a block device"
        val real = run("readlink", "-f", device).out.trim().ifEmpty { device }
        val marker = root.resolve(DEVICE_MARKER)
        val ours = runCatching { Files.readString(marker).trim() }.getOrNull() == real
        if (initialised && !ours)
        {
            return "TrieDB $device is not initialised by this node: run the Snapshot step first (it creates the TrieDB and imports the snapshot)"
        }
        val mounts = run("lsblk", "-rno", "MOUNTPOINTS", real)
        if (mounts.out.lineSequence().any { it.isNotBlank() })
        {
            return "TrieDB device $device (${real}) or one of its partitions is mounted: ${mounts.out.trim().lineSequence().first { it.isNotBlank() }}"
        }
        val sector = run("blockdev", "--getss", real).out.trim().toLongOrNull()
        if (sector != SECTOR_BYTES)
        {
            return "TrieDB device $device has a logical sector size of ${sector ?: "unknown"} bytes, Monad needs $SECTOR_BYTES " +
                "(format the namespace with `nvme format --lbaf=0` yourself — the toolkit never erases a drive on its own)"
        }
        val size = run("blockdev", "--getsize64", real).out.trim().toLongOrNull()
        if (size == null || size < MIN_DEVICE_BYTES)
        {
            return "TrieDB device $device is too small (${(size ?: 0) shr 30} GiB); the docs use a dedicated 2 TB NVMe"
        }
        if (!ours)
        {
            val sig = run("blkid", "-p", "-o", "export", real)
            if (sig.ok && sig.out.isNotBlank())
            {
                val what = sig.out.lineSequence().firstOrNull { it.startsWith("TYPE=") || it.startsWith("PTTYPE=") } ?: "a signature"
                return "TrieDB device $device already holds data ($what). The toolkit never erases an existing filesystem or partition table: " +
                    "point it at an empty partition, or wipe this one yourself with `wipefs -a $real`"
            }
        }
        return null
    }

    /** `monad-rpc --help` under [libDir]: library problems become an apt hint, missing flags a version-mismatch error. */
    fun checkRpcBinary(rpc: Path, libDir: Path): String?
    {
        val r = run(listOf(rpc.toString(), "--help"), mapOf("LD_LIBRARY_PATH" to libDir.toString()), 30)
        if (r.out.contains("error while loading shared libraries"))
        {
            val lib = r.out.substringAfter("loading shared libraries:").substringBefore(':').trim()
            return "monad-rpc cannot start: $lib — run the Host deps step (libcrypto++8t64 libboost-context1.83.0 libtbb12 liburing2 libarchive13t64 …), Ubuntu 24.04 required"
        }
        val missing = listOf("--rpc-addr", "--rpc-port", "--ws-port").filter { !r.out.contains(it) }
        if (missing.isNotEmpty())
        {
            return "monad-rpc of this version has no ${missing.joinToString()} — the unit flags were written for monad 0.16.x; check `monad-rpc --help`"
        }
        return null
    }

    /** Host RAM / kernel buffers like the vendor package's sysctl drop-in (needed for raptorcast throughput). */
    fun tuneKernel(sysctlFile: Path = Path.of(SYSCTL_FILE), apply: Boolean = true): String?
    {
        val body = """
            net.core.rmem_default=62500000
            net.core.rmem_max=62500000
            net.core.wmem_default=62500000
            net.core.wmem_max=62500000
            net.ipv4.tcp_rmem=4096 12582912 12582912
            net.ipv4.tcp_wmem=4096 12582912 12582912
        """.trimIndent() + "\n"
        return try
        {
            if (runCatching { Files.readString(sysctlFile) }.getOrNull() != body)
            {
                Files.writeString(sysctlFile, body)
            }
            if (apply && !run("sysctl", "-p", sysctlFile.toString()).ok) "sysctl -p $sysctlFile failed"
            else null
        }
        catch (e: Exception)
        {
            "cannot write $sysctlFile: ${e.message}"
        }
    }

    /** `KEYSTORE_PASSWORD` of the node, created once (64 hex chars) in `<root>/.toolkit/monad.env` (0600). */
    fun ensurePassword(root: Path): Result<String>
    {
        val file = root.resolve(ENV_FILE)
        return try
        {
            Files.createDirectories(file.parent)
            if (Files.isRegularFile(file))
            {
                val existing = Regex("KEYSTORE_PASSWORD='([0-9a-f]{64})'").find(Files.readString(file))?.groupValues?.get(1)
                if (existing != null) return Result.success(existing)
            }
            val password = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            writePrivate(file, "KEYSTORE_PASSWORD='$password'\n")
            Result.success(password)
        }
        catch (e: Exception)
        {
            Result.failure(e)
        }
    }

    /**
     * Creates `config/id-secp` and `config/id-bls` once (never overwrites a key). The tool prints the secret material; it goes to
     * `.toolkit/backup/{secp,bls}-backup` (0600) and nowhere else.
     */
    fun ensureKeystores(root: Path, state: Path, bin: (String) -> Path, libDir: Path, password: String): String?
    {
        val config = state.resolve("config")
        val secp = config.resolve("id-secp")
        val bls = config.resolve("id-bls")
        if (Files.exists(secp) && Files.exists(bls)) return null
        if (Files.exists(secp) != Files.exists(bls))
        {
            return "only one of $secp / $bls exists — restore both keystores from your backup or remove the other one"
        }
        return try
        {
            Files.createDirectories(config)
            Files.createDirectories(root.resolve(BACKUP_DIR))
            for ((type, path, backup) in listOf(
                Triple("secp", secp, "secp-backup"),
                Triple("bls", bls, "bls-backup"),
            ))
            {
                val r = run(
                    listOf(bin("monad-keystore").toString(), "create", "--key-type", type, "--keystore-path", path.toString(), "--password", password),
                    mapOf("LD_LIBRARY_PATH" to libDir.toString()),
                    60,
                )
                if (!r.ok || !Files.exists(path)) return "monad-keystore create ($type) failed: ${r.out.take(300)}"
                writePrivate(root.resolve(BACKUP_DIR).resolve(backup), r.out)
            }
            null
        }
        catch (e: Exception)
        {
            e.message ?: "keystore creation failed"
        }
    }

    /** Public IPv4 of the host (the name record advertises it); [override] wins when it is a valid address. */
    fun publicIp(override: String?): String?
    {
        override?.trim()?.takeIf { IPV4.matches(it) }?.let { return it }
        val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()
        for (url in listOf("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"))
        {
            val text = runCatching {
                client.send(
                    HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(8)).header("User-Agent", "rpcnode-agent").GET().build(),
                    HttpResponse.BodyHandlers.ofString(),
                ).body().trim()
            }.getOrNull() ?: continue
            if (IPV4.matches(text)) return text
        }
        return null
    }

    /** `monad-sign-name-record` for [env]: the flag set differs between mainnet and testnet (docs). Returns the printed `self_*` values. */
    fun signNameRecord(
        env: String,
        sign: Path,
        libDir: Path,
        ip: String,
        p2p: Int,
        authUdp: Int,
        directUdp: Int,
        keystore: Path,
        password: String,
        seq: Int,
    ): Result<Map<String, String>>
    {
        val args = buildList {
            add(sign.toString())
            addAll(listOf("--ip", ip, "--tcp-port", p2p.toString()))
            if (env == "testnet") addAll(listOf("--authenticated-udp-port", authUdp.toString(), "--direct-udp-port", directUdp.toString()))
            else addAll(listOf("--udp-port", p2p.toString(), "--authenticated-udp-port", authUdp.toString()))
            addAll(listOf("--keystore-path", keystore.toString(), "--password", password, "--self-record-seq-num", seq.toString()))
        }
        val r = run(args, mapOf("LD_LIBRARY_PATH" to libDir.toString()), 60)
        val values = MonadNodeToml.parseNameRecord(r.out)
        if (!r.ok || "self_name_record_sig" !in values)
        {
            return Result.failure(IllegalStateException("monad-sign-name-record failed: ${r.out.take(300)}"))
        }
        return Result.success(values)
    }

    /** Downloads [url] to [dest] atomically; false when it does not answer 200 with a body. */
    fun download(url: String, dest: Path): Boolean =
        runCatching {
            Files.createDirectories(dest.parent)
            val tmp = dest.resolveSibling("${dest.fileName}.tmp")
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build()
            val resp = client.send(
                HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).header("User-Agent", "rpcnode-agent").GET().build(),
                HttpResponse.BodyHandlers.ofFile(tmp),
            )
            if (resp.statusCode() != 200 || Files.size(tmp) == 0L)
            {
                Files.deleteIfExists(tmp)
                false
            }
            else
            {
                Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                true
            }
        }.getOrDefault(false)

    fun isHexPassword(s: String): Boolean = HEX64.matches(s)

    private fun writePrivate(file: Path, content: String)
    {
        Files.createDirectories(file.parent)
        Files.writeString(file, content)
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }

    data class Out(val ok: Boolean, val out: String)

    private fun run(vararg cmd: String): Out = run(cmd.toList(), emptyMap(), 20)

    private fun run(cmd: List<String>, env: Map<String, String>, timeoutSec: Long): Out =
        try
        {
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment().putAll(env)
            val p = pb.start()
            val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS))
            {
                p.destroyForcibly()
                Out(false, text + "\n(timed out after ${timeoutSec}s)")
            }
            else Out(p.exitValue() == 0, text)
        }
        catch (e: Exception)
        {
            Out(false, e.message.orEmpty())
        }
}
