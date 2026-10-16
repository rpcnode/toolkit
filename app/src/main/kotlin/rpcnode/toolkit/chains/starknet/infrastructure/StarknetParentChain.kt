package rpcnode.toolkit.chains.starknet.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Juno verifies the chain against the Starknet core contract on Ethereum and needs a WebSocket for that (`--eth-node`).
 * Provider URLs usually carry a key, so the value goes to a 0600 EnvironmentFile as `JUNO_ETH_NODE` (Juno reads
 * `JUNO_<FLAG>` variables) and never into the unit, the process arguments or launch.json.
 */
object StarknetParentChain
{
    const val ENV_FILE = ".toolkit/juno.env"
    const val ENV_KEY = "JUNO_ETH_NODE"

    /** The value sits in a `KEY="<url>"` line: no quote, backslash, `$`, `%`, backtick or whitespace. */
    private val SAFE_WS_URL = Regex("""^wss?://[^\s"'\\$%`]+$""")

    fun resolve(env: StarknetEnv, option: String?): String = option?.trim().orEmpty().ifEmpty { env.defaultEthNode }

    /** Null when [url] is acceptable, else the reason. */
    fun validate(url: String): String? =
        if (SAFE_WS_URL.matches(url.trim())) null
        else "Juno needs a WebSocket Ethereum endpoint (ws:// or wss://, no spaces, quotes, backslash, \$ or %) in l1_rpc, got an http(s) or malformed URL"

    fun envBody(url: String): String =
        "# managed by rpcnode starknet provision — Ethereum L1 WebSocket (may carry an API key), keep 0600\n$ENV_KEY=\"$url\"\n"

    fun writeEnvFile(nodeDir: Path, url: String): Path
    {
        val file = nodeDir.resolve(ENV_FILE)
        Files.createDirectories(file.parent)
        Files.deleteIfExists(file)
        // created 0600 up front: the key must never be readable, not even between write and chmod
        Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(file, envBody(url))
        return file
    }

    /** The URL a previous Start stored (a restart carries no `--toolkit-l1-rpc`: it is dropped before launch.json is written). */
    fun readEnvFile(nodeDir: Path): String?
    {
        val file = nodeDir.resolve(ENV_FILE)
        if (!Files.isRegularFile(file))
        {
            return null
        }
        return runCatching {
            Files.readAllLines(file).firstOrNull { it.startsWith("$ENV_KEY=") }?.substringAfter('=')?.trim()?.trim('"')
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
