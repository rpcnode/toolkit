package rpcnode.toolkit.chains.stacks.infrastructure

import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftTomlPatch

/**
 * `stacks.toml` = the tag's sample follower config with Start's keys set (comments and everything else stay).
 * Written 0600: it holds the Bitcoin RPC password. `[connection_options] auth_token` is never set (a follower has no signer).
 */
object StacksConfigToml
{
    private val SAFE_HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9._-]{0,251}[A-Za-z0-9])?$")

    /** A TOML basic string; null when it holds a control character (it could not be written on one line). */
    fun quote(raw: String): String?
    {
        if (raw.any { it.isISOControl() }) return null
        return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }

    fun validate(burn: StacksBurnchain): String?
    {
        if (!SAFE_HOST.matches(burn.host)) return "burnchain host must be a host name or IP address"
        if (burn.rpcPort !in 1..65535 || burn.p2pPort !in 1..65535) return "burnchain ports must be 1-65535"
        if (quote(burn.username) == null || quote(burn.password) == null) return "burnchain user / password must not contain control characters"
        return null
    }

    fun render(template: String, env: StacksEnv, workingDir: String, burn: StacksBurnchain?): String
    {
        var out = template
        fun set(section: String, key: String, value: String) { out = CometbftTomlPatch.set(out, section, key, value) }
        set("node", "working_dir", quote(workingDir) ?: error("working_dir has a control character"))
        set("node", "rpc_bind", "\"0.0.0.0:${env.rpcPort}\"")
        set("node", "p2p_bind", "\"0.0.0.0:${env.p2pPort}\"")
        // loopback: the metrics have no auth
        set("node", "prometheus_bind", "\"127.0.0.1:${env.metricsPort}\"")
        if (env.local && burn != null)
        {
            set("burnchain", "peer_host", quote(burn.host)!!)
            set("burnchain", "rpc_port", burn.rpcPort.toString())
            set("burnchain", "peer_port", burn.p2pPort.toString())
            if (burn.username.isNotEmpty()) set("burnchain", "username", quote(burn.username)!!)
            if (burn.password.isNotEmpty()) set("burnchain", "password", quote(burn.password)!!)
        }
        set("connection_options", "max_http_clients", StacksSettings.MAX_HTTP_CLIENTS.toString())
        return out
    }
}
