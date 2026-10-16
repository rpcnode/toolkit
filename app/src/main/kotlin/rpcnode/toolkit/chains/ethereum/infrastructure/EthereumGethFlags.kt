package rpcnode.toolkit.chains.ethereum.infrastructure

import java.util.Base64

/**
 * Tunable geth flags as key/value pairs, rendered into the systemd unit.
 *
 * [defaults] are what the toolkit has always set; the operator overrides or adds flags in Node config
 * (`install_options.geth_flags`, one `key=value` per line, key without the leading `--`). Flags that bind
 * ports, paths, the network or the sync mode are [LOCKED]: they come from the catalog and cannot be overridden.
 */
object EthereumGethFlags
{
    /** Keys that the catalog / node type decide — an override is ignored and reported. */
    val LOCKED: Set<String> = setOf(
        "datadir", "port", "http", "http.port", "http.addr", "ws", "ws.port", "ws.addr",
        "authrpc.addr", "authrpc.port", "authrpc.jwtsecret", "authrpc.vhosts",
        "syncmode", "gcmode", "state.scheme", "ipcdisable", "ipcpath", "history.chain",
        "mainnet", "sepolia", "holesky", "hoodi", "networkid", "config", "datadir.ancient",
    )

    private val KEY = Regex("^[a-z][a-z0-9.\\-]{0,63}$")

    /** Everything a value may contain: no spaces, quotes, `$`, `\`, `;`, backticks or newlines (it lands in a unit file). */
    private val VALUE = Regex("^[A-Za-z0-9_.,:/*@+=\\-]{0,256}$")

    /** Flags the unit sets unless overridden, in unit order. */
    fun defaults(env: String): LinkedHashMap<String, String> = linkedMapOf(
        "http.api" to "eth,net,web3,txpool",
        "http.vhosts" to "*",
        "ws.api" to "eth,net,web3,txpool",
        "ws.origins" to "*",
        "cache" to EthereumClusters.cacheMb(env).toString(),
        "maxpeers" to "100",
        "rpc.batch-request-limit" to "2000",
    )

    data class Parsed(val overrides: LinkedHashMap<String, String>, val rejected: List<String>)

    fun parse(text: String?): Parsed
    {
        val out = linkedMapOf<String, String>()
        val rejected = mutableListOf<String>()
        for (raw in text.orEmpty().lines())
        {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#"))
            {
                continue
            }
            val body = line.removePrefix("--")
            val eq = body.indexOfFirst { it == '=' || it == ' ' }
            val key = (if (eq < 0) body else body.substring(0, eq)).trim()
            val value = if (eq < 0) "" else body.substring(eq + 1).trim()
            if (!KEY.matches(key) || !VALUE.matches(value) || key in LOCKED)
            {
                rejected += line
                continue
            }
            out[key] = value
        }
        return Parsed(out, rejected)
    }

    /** Defaults with the operator's overrides applied (new keys are appended). */
    fun effective(env: String, text: String?): LinkedHashMap<String, String>
    {
        val merged = defaults(env)
        merged.putAll(parse(text).overrides)
        return merged
    }

    /** ` \`-continued lines for the unit's ExecStart, each starting with two spaces and `--`. */
    fun render(env: String, text: String?): String =
        effective(env, text).entries.joinToString("") { (k, v) ->
            " \\\n  --$k" + if (v.isEmpty()) "" else " $v"
        }

    /** Launch args cannot carry newlines, so the text travels base64-encoded. */
    fun encode(text: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    fun decode(encoded: String): String =
        runCatching { String(Base64.getUrlDecoder().decode(encoded)) }.getOrDefault("")
}
