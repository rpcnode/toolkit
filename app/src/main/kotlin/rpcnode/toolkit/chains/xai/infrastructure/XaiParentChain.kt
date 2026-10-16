package rpcnode.toolkit.chains.xai.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Arbitrum One RPC of an Xai node (`install_options.l1_rpc`). There is no beacon: the parent chain is an Arbitrum chain,
 * Nitro reads its batches with plain `eth_getLogs`.
 */
object XaiParentChain
{
    private val json = Json { ignoreUnknownKeys = true }

    /** The unit reads the value from an EnvironmentFile line `KEY="<url>"`: no quote, backslash, `$`, `%` or whitespace allowed. */
    private val SAFE_URL = Regex("""^https?://[^\s"'\\$%`]+$""")

    /** Explicit option → public default (stop-gap, rate limited). */
    fun resolve(optionValue: String?): String =
        optionValue?.trim().orEmpty().ifEmpty { XaiChain.DEFAULT_PARENT_RPC }

    fun validate(rpc: String): String?
    {
        val url = rpc.trim()
        return if (SAFE_URL.matches(url)) null else "xai parent RPC must be an http(s):// URL without spaces, quotes, backslash, \$ or %"
    }

    fun fromInstallOptions(installOptionsJson: String?): String
    {
        val raw = installOptionsJson?.trim().orEmpty()
        val value = if (raw.isEmpty())
        {
            null
        }
        else
        {
            runCatching { json.parseToJsonElement(raw).jsonObject["l1_rpc"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        return resolve(value)
    }

    /** Writes [rpc] to `<node_dir>/.toolkit/xai-parent.env` readable by the owner only. */
    fun writeEnvFile(nodeDir: Path, rpc: String): Path
    {
        val file = nodeDir.resolve(XaiChain.PARENT_ENV_FILE)
        Files.createDirectories(file.parent)
        Files.deleteIfExists(file)
        // created 0600 up front: the key must never be readable, not even for the moment between write and chmod
        Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(file, XaiUnitBodies.parentEnv(rpc))
        return file
    }

    /** The URL a previous Start stored (a restart carries no `--toolkit-l1-rpc`: the starter drops it before launch.json is written). */
    fun readEnvFile(nodeDir: Path): String?
    {
        val file = nodeDir.resolve(XaiChain.PARENT_ENV_FILE)
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching {
            Files.readAllLines(file)
                .firstOrNull { it.startsWith("${XaiChain.PARENT_ENV_KEY}=") }
                ?.substringAfter('=')?.trim()?.trim('"')
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
