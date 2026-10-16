package rpcnode.toolkit.chains.nonevm.infrastructure

import org.yaml.snakeyaml.Yaml

/** What the panel shows (network.yml bindings, clients.yml ports) read back from the shipped resources, for catalog == code tests. */
object CatalogFixtures
{
    data class Binding(
        val key: String,
        val source: String,
        val value: String?,
        val role: String?,
        val option: String?,
        val default: String?,
        val map: Map<String, String>,
        val onlyEnv: List<String>,
        val whenOption: String?,
        /** `whenInstallOptionValue`: the option value(s), comma separated, that switch the binding on. */
        val whenOptionValue: String? = null,
    )

    private fun resource(path: String): String =
        checkNotNull(CatalogFixtures::class.java.classLoader.getResourceAsStream(path)) { "missing $path" }.readBytes().toString(Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    fun yaml(path: String): Map<String, Any?> = Yaml().load<Any?>(resource(path)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun bindings(network: String): List<Binding> =
        (((yaml("chains/$network/network.yml")["clientConfig"] as Map<String, Any?>)["bindings"]) as List<Map<String, Any?>>).map {
            Binding(
                key = it["key"].toString(),
                source = it["source"].toString(),
                value = it["value"]?.toString(),
                role = it["role"]?.toString(),
                option = it["option"]?.toString(),
                default = it["default"]?.toString(),
                map = (it["map"] as? Map<String, Any?>)?.mapValues { e -> e.value.toString() }.orEmpty(),
                onlyEnv = (it["onlyEnv"] as? List<String>).orEmpty(),
                whenOption = it["whenInstallOption"]?.toString(),
                whenOptionValue = it["whenInstallOptionValue"]?.toString(),
            )
        }

    /** Bindings that apply to [env] (those without `onlyEnv`, plus the ones listing it). */
    fun bindingsFor(network: String, env: String): List<Binding> =
        bindings(network).filter { it.onlyEnv.isEmpty() || env in it.onlyEnv }

    @Suppress("UNCHECKED_CAST")
    fun envIds(network: String): List<String> =
        (yaml("chains/$network/network.yml")["envs"] as List<Map<String, Any?>>).map { it["id"].toString() }

    @Suppress("UNCHECKED_CAST")
    fun publicTip(network: String, env: String): List<String> =
        ((yaml("chains/$network/network.yml")["envs"] as List<Map<String, Any?>>).single { it["id"] == env }["publicTip"] as Map<String, Any?>)
            .let { it["urls"] as List<String> }

    /** `role -> port` of the program of [env] in clients.yml. */
    @Suppress("UNCHECKED_CAST")
    fun ports(network: String, env: String): Map<String, Int> =
        ((yaml("chains/$network/clients.yml")["programs"] as List<Map<String, Any?>>).single { it["env"] == env }["ports"] as List<Map<String, Any?>>)
            .associate { it["role"].toString() to (it["port"] as Int) }

    /** `--key=value` flags of a unit as a key → value map (bare `--flag` = "true"; a leading sub-command item is skipped). */
    fun flagMap(flags: List<String>): Map<String, String> =
        flags.mapNotNull { f ->
            val token = f.trim().split(' ').firstOrNull { it.startsWith("--") } ?: return@mapNotNull null
            val body = token.removePrefix("--")
            if ('=' in body) body.substringBefore('=') to body.substringAfter('=') else body to "true"
        }.toMap()
}
