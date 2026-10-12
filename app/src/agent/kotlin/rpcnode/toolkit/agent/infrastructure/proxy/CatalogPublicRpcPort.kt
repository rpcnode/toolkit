package rpcnode.toolkit.agent.infrastructure.proxy

/**
 * The public (login-free) RPC proxy port of a node, from the shipped catalog
 * (`chains/<network>/clients.yml`, role `rpc_public`). The agent jar carries those files, so the agent knows the
 * port without the panel having to pass it around.
 */
object CatalogPublicRpcPort
{
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Int>>()

    fun find(network: String, env: String): Int? = find(network, env, ROLE)

    /** A catalog port of any [role] (e.g. TON's `indexer`, the v3 API behind the router). */
    fun find(network: String, env: String, role: String): Int?
    {
        val net = network.trim().lowercase()
        if (net.isEmpty() || !net.matches(Regex("[a-z0-9_-]+")))
        {
            return null
        }
        return load(net, role)[env.trim().lowercase()]
    }

    const val ROLE = "rpc_public"

    private fun load(network: String, role: String): Map<String, Int> = cache.getOrPut("$network/$role") {
        val text = CatalogPublicRpcPort::class.java.getResourceAsStream("/chains/$network/clients.yml")
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: return@getOrPut emptyMap()
        parse(text, role)
    }

    internal fun parse(text: String, role: String = ROLE): Map<String, Int>
    {
        val out = linkedMapOf<String, Int>()
        var env = ""
        val envRe = Regex("""^\s*-\s*env:\s*([\w.-]+)""")
        val portRe = Regex("""role:\s*${Regex.escape(role)},\s*port:\s*(\d+)""")
        for (line in text.lineSequence())
        {
            envRe.find(line)?.let { env = it.groupValues[1].lowercase() }
            val port = portRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (env.isNotEmpty() && port in 1..65_535)
            {
                out[env] = port
            }
        }
        return out
    }
}
