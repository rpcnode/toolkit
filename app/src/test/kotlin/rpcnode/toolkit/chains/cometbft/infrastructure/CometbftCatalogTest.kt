package rpcnode.toolkit.chains.cometbft.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml

/** What the panel shows (network.yml bindings, clients.yml ports) must be what the node runs. */
class CometbftCatalogTest
{
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }.readBytes().toString(Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    private fun yaml(path: String): Map<String, Any?> = Yaml().load<Any?>(resource(path)) as Map<String, Any?>

    @Test
    fun literal_bindings_equal_the_applied_settings()
    {
        val problems = mutableListOf<String>()
        for (profile in CometbftProfiles.ALL)
        {
            val net = yaml("chains/${profile.network.value}/network.yml")
            @Suppress("UNCHECKED_CAST")
            val bindings = ((net["clientConfig"] as Map<String, Any?>)["bindings"] as List<Map<String, Any?>>)
            val literals = bindings.filter { it["source"] == "literal" }.associate { it["key"].toString() to it["value"].toString() }
            for (env in profile.envs)
            {
                val settings = CometbftSettings.forEnv(profile, env).associate { it.bindingKey to it.value }
                for ((key, value) in literals)
                {
                    if (key == "LimitNOFILE") { if (value != CometbftSettings.NOFILE.toString()) problems += "${profile.network}: LimitNOFILE $value"; continue }
                    val applied = settings[key]
                    if (applied == null) problems += "${profile.network}/${env.id}: binding $key not applied"
                    else if (applied != value && env.id == profile.envs.first().id && key != "minimum-gas-prices" && key != "evm.evm-chain-id")
                        problems += "${profile.network}/${env.id}: $key binding=$value applied=$applied"
                }
                for ((key, value) in settings)
                {
                    if (key == "p2p.seeds") continue
                    if (key !in literals && bindings.none { it["key"] == key })
                        problems += "${profile.network}/${env.id}: applied $key=$value has no binding"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun ports_in_clients_yml_equal_the_profile()
    {
        for (profile in CometbftProfiles.ALL)
        {
            @Suppress("UNCHECKED_CAST")
            val programs = yaml("chains/${profile.network.value}/clients.yml")["programs"] as List<Map<String, Any?>>
            for (env in profile.envs)
            {
                val program = programs.single { it["env"] == env.id }
                @Suppress("UNCHECKED_CAST")
                val ports = (program["ports"] as List<Map<String, Any?>>).associate { it["role"].toString() to (it["port"] as Int) }
                val p = profile.ports(env)
                val expected = buildMap {
                    put(profile.rpcRole, p.rpc); put("p2p", p.p2p); put("metrics", p.metrics); put("api", p.api); put("grpc", p.grpc)
                    p.evmHttp?.let { put("http", it) }; p.evmWs?.let { put("ws", it) }; p.grpcLaddr?.let { put("grpc_laddr", it) }
                }
                for ((role, port) in expected) assertEquals(port, ports[role], "${profile.network}/${env.id} $role")
            }
        }
    }

    @Test
    fun yaml_envs_equal_the_profile_envs()
    {
        for (profile in CometbftProfiles.ALL)
        {
            @Suppress("UNCHECKED_CAST")
            val envs = (yaml("chains/${profile.network.value}/network.yml")["envs"] as List<Map<String, Any?>>).map { it["id"].toString() }
            assertEquals(profile.envs.map { it.id }, envs, profile.network.value)
        }
    }

    @Test
    fun every_network_renders_its_unit()
    {
        for (profile in CometbftProfiles.ALL)
        {
            val env = profile.envs.first()
            val body = CometbftUnitBodies.unit(profile, env, "/n", "/n/bin/${profile.binary} start", "/n/logs/x.log")
            assertTrue(!body.contains("{{"), body)
            assertTrue(body.contains("LimitNOFILE=1048576"))
        }
    }
}
