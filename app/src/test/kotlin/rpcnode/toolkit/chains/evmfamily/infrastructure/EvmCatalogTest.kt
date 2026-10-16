package rpcnode.toolkit.chains.evmfamily.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.yaml.snakeyaml.Yaml
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmTestSupport.flags

/** What the panel shows (network.yml bindings, clients.yml ports) must be what the node runs. */
class EvmCatalogTest
{
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }.readBytes().toString(Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    private fun yaml(path: String): Map<String, Any?> = Yaml().load<Any?>(resource(path)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun bindings(id: String): List<Map<String, Any?>> =
        ((yaml("chains/$id/network.yml")["clientConfig"] as Map<String, Any?>)["bindings"] as List<Map<String, Any?>>)

    @Test
    fun ships_the_expected_networks()
    {
        assertEquals(
            listOf("gnosis", "joc", "flare", "sonic", "kaia", "vana", "tempo", "berachain", "fluent", "gravity", "monad"),
            EvmProfiles.ALL.map { it.network.value },
        )
    }

    @Test
    fun yaml_envs_and_disk_roles_equal_the_profile()
    {
        for (p in EvmProfiles.ALL)
        {
            val net = yaml("chains/${p.network.value}/network.yml")
            @Suppress("UNCHECKED_CAST")
            val envs = (net["envs"] as List<Map<String, Any?>>).map { it["id"].toString() }
            assertEquals(p.envs.map { it.id }, envs, p.network.value)
            @Suppress("UNCHECKED_CAST")
            val roles = (net["diskRoles"] as List<Map<String, Any?>>).map { it["id"].toString() }
            assertEquals(p.diskRoles, roles, "${p.network.value} disk roles")
        }
    }

    @Test
    fun ports_in_clients_yml_equal_the_profile()
    {
        for (p in EvmProfiles.ALL)
        {
            @Suppress("UNCHECKED_CAST")
            val programs = yaml("chains/${p.network.value}/clients.yml")["programs"] as List<Map<String, Any?>>
            for (env in p.envs)
            {
                val withPorts = programs.filter { it["env"] == env.id && it["ports"] != null }
                assertEquals(1, withPorts.size, "${p.network.value}/${env.id}: exactly one program carries the ports")
                @Suppress("UNCHECKED_CAST")
                val ports = (withPorts.single()["ports"] as List<Map<String, Any?>>).associate { it["role"].toString() to (it["port"] as Int) }
                assertEquals(p.ports(env), ports, "${p.network.value}/${env.id}")
                @Suppress("UNCHECKED_CAST")
                val log = (withPorts.single()["requirements"] as Map<String, Any?>)["logFile"]
                assertEquals(p.relativeLog(), log, "${p.network.value} log file")
            }
        }
    }

    @Test
    fun catalog_port_bindings_name_existing_roles_and_disk_bindings_existing_disk_roles()
    {
        for (p in EvmProfiles.ALL)
        {
            for (b in bindings(p.network.value))
            {
                when (b["source"])
                {
                    "catalog_port" -> assertTrue(b["role"] in p.portOffsets, "${p.network.value}: port role ${b["role"]}")
                    "disk_role_dir" -> assertTrue(b["role"] in p.diskRoles, "${p.network.value}: disk role ${b["role"]}")
                }
            }
        }
    }

    @Test
    fun literal_bindings_equal_the_rendered_flags()
    {
        val problems = mutableListOf<String>()
        for (p in EvmProfiles.ALL)
        {
            val literals = bindings(p.network.value).filter { it["source"] == "literal" }.associate { it["key"].toString() to it["value"].toString() }
            for (env in p.envs)
            {
                val units = p.recipe.units(EvmTestSupport.context(p, env.id))
                val rendered = EvmTestSupport.allUnits(units).map { flags(it) }
                for ((key, value) in literals)
                {
                    if (key == "limit-nofile")
                    {
                        for (u in EvmTestSupport.allUnits(units))
                        {
                            if (EvmTestSupport.unitSetting(u, "LimitNOFILE") != p.nofile.toString()) problems += "${p.network.value}: LimitNOFILE != $value"
                        }
                        if (value != p.nofile.toString()) problems += "${p.network.value}/${env.id}: limit-nofile binding $value, unit ${p.nofile}"
                        continue
                    }
                    val targets = p.literalFlags[key] ?: continue
                    for (flag in targets)
                    {
                        val applied = rendered.firstNotNullOfOrNull { it[flag] }?.firstOrNull()
                        if (applied == null) problems += "${p.network.value}/${env.id}: $flag of binding $key not in the unit"
                        else if (applied != value) problems += "${p.network.value}/${env.id}: $key binding=$value unit $flag=$applied"
                    }
                }
            }
            for (key in p.literalFlags.keys)
            {
                if (key !in literals) problems += "${p.network.value}: literalFlags names unknown binding $key"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun every_unit_renders_without_placeholders_and_with_a_log_file()
    {
        for (p in EvmProfiles.ALL)
        {
            for (env in p.envs)
            {
                val units = p.recipe.units(EvmTestSupport.context(p, env.id))
                for (u in EvmTestSupport.allUnits(units))
                {
                    assertTrue(!u.contains("{{"), "${p.network.value}: placeholder left in\n$u")
                    assertTrue(u.contains("StandardOutput=append:") && u.contains("Restart="), u)
                }
                // Monad has no JWT but runs consensus, RPC and a huge-pages oneshot as companions of the execution unit
                if (p.network.value == "monad") assertEquals(5, units.companions.size)
                else assertEquals(units.companions.size, if (p.recipe.usesJwt && p.portOffsets.containsKey("engine") && p.network.value != "joc") units.companions.size else 0)
            }
        }
    }

    @Test
    fun primary_log_is_the_profile_log()
    {
        for (p in EvmProfiles.ALL)
        {
            val ctx = EvmTestSupport.context(p)
            val primary = p.recipe.units(ctx).primary
            assertTrue(primary.contains("append:${ctx.root.resolve(p.relativeLog())}"), "${p.network.value}\n$primary")
        }
    }

    @Test
    fun public_tip_urls_are_plain_endpoints_without_secrets()
    {
        for (p in EvmProfiles.ALL)
        {
            @Suppress("UNCHECKED_CAST")
            val envs = yaml("chains/${p.network.value}/network.yml")["envs"] as List<Map<String, Any?>>
            for (e in envs)
            {
                @Suppress("UNCHECKED_CAST")
                val urls = ((e["publicTip"] as Map<String, Any?>)["urls"] as List<String>)
                assertTrue(urls.isNotEmpty())
                for (u in urls) assertTrue(u.startsWith("https://") && !u.contains('?') && !Regex("(?i)key|token|secret").containsMatchIn(u), u)
            }
        }
    }

    @Test
    fun sub_two_second_chains_set_a_tip_lag()
    {
        for (id in listOf("sonic", "tempo", "berachain", "flare", "kaia", "gravity", "fluent"))
        {
            @Suppress("UNCHECKED_CAST")
            val envs = yaml("chains/$id/network.yml")["envs"] as List<Map<String, Any?>>
            for (e in envs) assertNotNull(e["tipLagBlocks"], "$id/${e["id"]} needs tipLagBlocks")
        }
    }

    @Test
    fun snapshot_policy_matches_the_profile()
    {
        for (p in EvmProfiles.ALL)
        {
            @Suppress("UNCHECKED_CAST")
            val envs = yaml("chains/${p.network.value}/network.yml")["envs"] as List<Map<String, Any?>>
            for (e in envs)
            {
                val policy = e["snapshot"]
                if (policy == "never") assertTrue(e["snapshotTypes"] == null, "${p.network.value}/${e["id"]}: never has no types")
                else
                {
                    assertNotNull(p.snapshot, "${p.network.value}/${e["id"]}: $policy needs a resolver")
                    assertNotNull(e["snapshotTypes"], "${p.network.value}/${e["id"]}: $policy needs snapshotTypes")
                }
            }
        }
    }

    @Test
    fun release_sources_follow_the_documented_rules()
    {
        val catalog = rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog()
        fun program(net: rpcnode.toolkit.catalog.domain.NetworkId, env: String, id: String) =
            catalog.programsFor(net, rpcnode.toolkit.catalog.domain.EnvId.parse(env)!!).single { it.programId == id }
        fun github(net: rpcnode.toolkit.catalog.domain.NetworkId, env: String, id: String) =
            kotlin.test.assertIs<rpcnode.toolkit.clients.domain.model.ClientVersionSource.GitHubRelease>(program(net, env, id).source)
        val n = rpcnode.toolkit.catalog.domain.NetworkId
        // the testnet line of Gravity is a `-testnet` prerelease; mainnet follows stable
        assertEquals("-testnet", github(n.GRAVITY, "testnet", "gravity_node").tagSuffix)
        assertTrue(github(n.GRAVITY, "testnet", "gravity_node").includePrerelease)
        assertNull(github(n.GRAVITY, "mainnet", "gravity_node").tagSuffix)
        // tempoxyz/tempo also tags crates
        assertEquals("v", github(n.TEMPO, "mainnet", "tempo").tagPrefix)
        // the patched geth lives in the vana monorepo next to app releases
        assertEquals("geth-v", github(n.VANA, "mainnet", "geth").tagPrefix)
        kotlin.test.assertIs<rpcnode.toolkit.clients.domain.model.ClientVersionSource.Pinned>(program(n.VANA, "moksha", "geth").source)
        for (env in listOf("mainnet", "moksha")) assertEquals("vana-com/prysm", github(n.VANA, env, "prysm-beacon").repo)
        // Bepolia runs a release candidate whose successors have no assets
        val bepolia = kotlin.test.assertIs<rpcnode.toolkit.clients.domain.model.ClientVersionSource.Pinned>(program(n.BERACHAIN, "bepolia", "beacond").source)
        assertEquals("v1.4.2-rc.0", bepolia.tag)
        for (env in listOf("mainnet", "chiado"))
        {
            assertTrue(program(n.GNOSIS, env, "nethermind").artifacts.single().urlTemplate.startsWith("github-asset:"))
        }
    }
}
