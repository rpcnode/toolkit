package rpcnode.toolkit.chains.bitcore.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import kotlin.io.path.createDirectories

/**
 * Dash / LTC / Doge / BCH need explicit `rpcuser` / `rpcpassword` (Go PrepareCoreLike).
 * Bitcoin Core stays cookie-only — do not call [ensure] for bitcoin.
 *
 * Credentials are preserved from an existing conf, else `.toolkit/rpc-auth.env`, else generated.
 * The env file is also what a leaf Go RPC proxy reads as `BITCOIN_RPC_USER` / `PASSWORD`.
 */
object BitcoreRpcAuth
{
    private val random = SecureRandom()

    private val rpcUserNetworks = setOf("dash", "ltc", "doge", "bch")

    data class Credentials(val user: String, val password: String)

    fun needsRpcUserPassword(network: String): Boolean =
        network.trim().lowercase() in rpcUserNetworks

    /**
     * Resolve credentials and persist `.toolkit/rpc-auth.env` under [nodeDir].
     * [existingConf] is the conf text before this sync's auth keys are applied (may be null).
     */
    fun ensure(nodeDir: Path, existingConf: String?): Credentials
    {
        val fromConf = readFromConf(existingConf)
        val fromEnv = readFromEnvFile(authEnvPath(nodeDir))
        val creds = when
        {
            fromConf != null -> fromConf
            fromEnv != null -> fromEnv
            else -> Credentials(user = "rpcnode", password = randomHex(24))
        }
        writeAuthEnv(nodeDir, creds)
        return creds
    }

    fun authEnvPath(nodeDir: Path): Path =
        nodeDir.resolve(".toolkit").resolve("rpc-auth.env")

    /**
     * Best-effort: if a Go leaf `toolkit.env` still exists, keep BITCOIN_RPC_* in sync
     * so the public fullnode proxy can inject Basic auth.
     */
    fun upsertGoToolkitEnv(network: String, env: String, creds: Credentials)
    {
        val n = network.trim().lowercase()
        val e = env.trim().lowercase()
        if (n.isEmpty() || e.isEmpty())
        {
            return
        }
        val path = Path.of("/etc", n, e, "toolkit.env")
        if (!Files.isRegularFile(path))
        {
            return
        }
        try
        {
            val raw = Files.readString(path)
            val next = upsertEnvKeys(
                raw,
                mapOf(
                    "BITCOIN_RPC_USER" to creds.user,
                    "BITCOIN_RPC_PASSWORD" to creds.password,
                ),
            )
            if (next != raw)
            {
                Files.writeString(path, next)
            }
        }
        catch (_: Exception)
        {
            // leaf may be gone; conf + .toolkit/rpc-auth.env still apply
        }
    }

    fun assignments(creds: Credentials): Map<String, String> =
        mapOf(
            "rpcuser" to creds.user,
            "rpcpassword" to creds.password,
        )

    fun readFromConf(text: String?): Credentials?
    {
        if (text.isNullOrBlank())
        {
            return null
        }
        var user: String? = null
        var pass: String? = null
        for (line in text.lineSequence())
        {
            val t = line.trim()
            if (t.startsWith("#") || t.isEmpty())
            {
                continue
            }
            when
            {
                t.startsWith("rpcuser=", ignoreCase = true) ->
                    user = t.substringAfter('=').trim().ifEmpty { null }
                t.startsWith("rpcpassword=", ignoreCase = true) ->
                    pass = t.substringAfter('=').trim().ifEmpty { null }
            }
        }
        val u = user ?: return null
        val p = pass ?: return null
        return Credentials(u, p)
    }

    fun readFromEnvFile(path: Path): Credentials?
    {
        if (!Files.isRegularFile(path))
        {
            return null
        }
        return try
        {
            var user: String? = null
            var pass: String? = null
            for (line in Files.readAllLines(path))
            {
                val t = line.trim()
                if (t.startsWith("#") || t.isEmpty() || !t.contains('='))
                {
                    continue
                }
                val key = t.substringBefore('=').trim()
                val value = t.substringAfter('=').trim()
                when (key)
                {
                    "BITCOIN_RPC_USER" -> user = value.ifEmpty { null }
                    "BITCOIN_RPC_PASSWORD" -> pass = value.ifEmpty { null }
                }
            }
            val u = user ?: return null
            val p = pass ?: return null
            Credentials(u, p)
        }
        catch (_: Exception)
        {
            null
        }
    }

    internal fun upsertEnvKeys(raw: String, keys: Map<String, String>): String
    {
        val found = mutableSetOf<String>()
        val lines = raw.lineSequence().map { line ->
            val t = line.trim()
            if (t.startsWith("#") || !t.contains('='))
            {
                return@map line
            }
            val key = t.substringBefore('=').trim()
            val replacement = keys[key] ?: return@map line
            found += key
            "$key=$replacement"
        }.toMutableList()
        for ((key, value) in keys)
        {
            if (key !in found)
            {
                lines += "$key=$value"
            }
        }
        val body = lines.joinToString("\n").trimEnd()
        return if (body.isEmpty()) body else "$body\n"
    }

    private fun writeAuthEnv(nodeDir: Path, creds: Credentials)
    {
        val path = authEnvPath(nodeDir)
        path.parent?.createDirectories()
        val body =
            "# managed by rpcnode bitcore rpc auth\n" +
                "BITCOIN_RPC_USER=${creds.user}\n" +
                "BITCOIN_RPC_PASSWORD=${creds.password}\n"
        Files.writeString(
            path,
            body,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        try
        {
            path.toFile().setReadable(false, false)
            path.toFile().setWritable(false, false)
            path.toFile().setReadable(true, true)
            path.toFile().setWritable(true, true)
        }
        catch (_: Exception)
        {
            // best-effort mode bits
        }
    }

    private fun randomHex(bytes: Int): String
    {
        val buf = ByteArray(bytes)
        random.nextBytes(buf)
        return buf.joinToString("") { b -> "%02x".format(b) }
    }
}
