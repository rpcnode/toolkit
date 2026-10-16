package rpcnode.toolkit.chains.evmfamily.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmTestSupport.command
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmTestSupport.context
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmTestSupport.flags
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmTestSupport.unitSetting
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.BerachainProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.FlareProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.GnosisProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.GravityProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.JocProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.KaiaProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.SonicProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.TempoProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.VanaProfile

class EvmRecipesTest
{
    private val sensitive = Regex("""(?i)(^|[,\s])(debug|admin|personal|txpool|trace|engine|miner)([,\s]|$)""")

    // --- exposure rules for every network ---

    @Test
    fun public_namespaces_never_include_debug_admin_or_engine_and_engine_metrics_stay_on_loopback()
    {
        for (p in EvmProfiles.ALL)
        {
            for (env in p.envs)
            {
                val ctx = context(p, env.id)
                for (u in EvmTestSupport.allUnits(p.recipe.units(ctx)))
                {
                    val f = flags(u)
                    for (key in listOf("--http.api", "--ws.api", "--rpcapi", "--wsapi", "--JsonRpc.EnabledModules"))
                    {
                        f[key]?.forEach { assertFalse(sensitive.containsMatchIn(it), "${p.network.value} $key=$it") }
                    }
                    for (key in f.keys.filter { it.startsWith("--authrpc.addr") })
                    {
                        assertEquals("127.0.0.1", f.getValue(key).single(), "${p.network.value} authrpc binds loopback")
                    }
                    f["--metrics.addr"]?.forEach { assertEquals("127.0.0.1", it, "${p.network.value} metrics.addr") }
                    f["--metrics"]?.filter { it.isNotEmpty() }?.forEach { assertTrue(it.startsWith("127.0.0.1:"), "${p.network.value} --metrics $it") }
                    f["--JsonRpc.EngineHost"]?.forEach { assertEquals("127.0.0.1", it) }
                    f["--JsonRpc.Host"]?.forEach { assertEquals("0.0.0.0", it) }
                    f["--ipcdisable"]?.let { /* present */ }
                    assertFalse(u.contains("--allow-insecure-unlock") || u.contains("--rpc.allow-js-tracers") || u.contains("--rpc.unsafe-debug"), p.network.value)
                }
            }
        }
    }

    @Test
    fun secrets_never_appear_in_unit_arguments_only_paths()
    {
        for (p in EvmProfiles.ALL)
        {
            val ctx = context(p)
            for (u in EvmTestSupport.allUnits(p.recipe.units(ctx)))
            {
                assertFalse(Regex("[0-9a-f]{64}").containsMatchIn(u.lines().first { it.startsWith("ExecStart=") }), "${p.network.value} has a 32-byte hex in the command line")
            }
        }
    }

    // --- gnosis / joc (Nethermind) ---

    @Test
    fun gnosis_runs_nethermind_with_a_loopback_engine_and_a_lighthouse_companion()
    {
        val ctx = context(GnosisProfile.PROFILE, "mainnet")
        val units = GnosisProfile.PROFILE.recipe.units(ctx)
        val el = flags(units.primary)
        assertTrue(command(units.primary).endsWith("bin/nethermind -c gnosis"), command(units.primary))
        assertEquals(listOf("41570"), el["--JsonRpc.Port"])
        assertEquals(listOf("41571"), el["--JsonRpc.WebSocketsPort"])
        assertEquals(listOf("41573"), el["--JsonRpc.EnginePort"])
        assertEquals(listOf(ctx.root.resolve(".toolkit/jwt.hex").toString()), el["--JsonRpc.JwtSecretFile"])
        assertEquals(listOf("${ctx.dir("execution")}/data"), el["--data-dir"])
        assertTrue(unitSetting(units.primary, "Environment")!!.startsWith("DOTNET_BUNDLE_EXTRACT_BASE_DIR="))
        val (name, cl) = units.companions.single()
        assertEquals("rpcnode-gnosis-consensus-mainnet.service", name)
        val lh = flags(cl)
        assertEquals(listOf("gnosis"), lh["--network"])
        assertEquals(listOf("https://checkpoint.gnosischain.com"), lh["--checkpoint-sync-url"])
        assertEquals(listOf("127.0.0.1"), lh["--http-address"])
        assertEquals(listOf("41577"), lh["--quic-port"])
        assertTrue(cl.contains("After=network-online.target rpcnode-gnosis-mainnet.service"))
    }

    @Test
    fun archive_mode_selects_the_archive_config_and_chiado_has_its_own_checkpoint()
    {
        val ctx = context(GnosisProfile.PROFILE, "chiado", EvmLaunchOptions("chiado", mode = EvmLaunchOptions.ARCHIVE))
        val units = GnosisProfile.PROFILE.recipe.units(ctx)
        assertTrue(command(units.primary).endsWith("-c chiado_archive"))
        assertEquals(listOf("https://checkpoint.chiadochain.net"), flags(units.companions.single().second)["--checkpoint-sync-url"])
        val joc = JocProfile.PROFILE.recipe.units(context(JocProfile.PROFILE, "testnet", EvmLaunchOptions("testnet", mode = "archive")))
        assertTrue(command(joc.primary).endsWith("-c joc-testnet_archive"))
    }

    @Test
    fun joc_has_no_engine_api_and_no_companion()
    {
        val units = JocProfile.PROFILE.recipe.units(context(JocProfile.PROFILE, "mainnet"))
        assertTrue(units.companions.isEmpty())
        val f = flags(units.primary)
        assertFalse(f.keys.any { it.contains("Engine") || it.contains("Jwt") })
        assertEquals(listOf("41780"), f["--JsonRpc.Port"])
        assertFalse(JocProfile.PROFILE.recipe.usesJwt)
    }

    // --- flare ---

    @Test
    fun flare_uses_the_avalanche_flags_with_the_looked_up_bootstrap_peer()
    {
        val ctx = context(FlareProfile.PROFILE, "coston2")
        val f = flags(FlareProfile.PROFILE.recipe.units(ctx).primary)
        assertEquals(listOf("costwo"), f["--network-id"])
        assertEquals(listOf("41470"), f["--http-port"])
        assertEquals(listOf("41472"), f["--staking-port"])
        assertEquals(listOf("34.79.60.151:9651"), f["--bootstrap-ips"])
        assertEquals(listOf("NodeID-NCfvDEdDv8tuVDxWLB1ShJToQ7qAcca2B"), f["--bootstrap-ids"])
        assertEquals(listOf("false"), f["--api-admin-enabled"])
    }

    @Test
    fun flare_c_chain_config_prunes_unless_archive_and_has_only_public_apis()
    {
        val full = FlareProfile.cChainConfig(archive = false)
        assertTrue("\"pruning-enabled\": true" in full && "state-sync-enabled" !in full)
        val archive = FlareProfile.cChainConfig(archive = true)
        assertTrue("\"pruning-enabled\": false" in archive && "\"state-sync-enabled\": false" in archive)
        assertFalse("debug" in full || "tx-pool" in full || "snowman" in full)
        assertTrue("\"admin-api-enabled\": false" in full)
    }

    // --- sonic ---

    @Test
    fun sonic_limits_follow_the_docs_and_the_host_ram()
    {
        val ctx = context(SonicProfile.PROFILE, "mainnet", mem = 64)
        val unit = SonicProfile.PROFILE.recipe.units(ctx).primary
        val f = flags(unit)
        assertEquals(listOf("12000"), f["--cache"])
        assertEquals(listOf("eth,net,web3,sonic"), f["--http.api"])
        assertEquals(listOf("127.0.0.1"), f["--metrics.addr"])
        assertTrue("--ipcdisable" in f)
        assertEquals("934073", unitSetting(unit, "LimitNOFILE"))
        assertTrue(unit.contains("Environment=GOMEMLIMIT=57GiB"), unit)
        assertTrue(unit.contains("Environment=GOTRACEBACK=crash"))
        val small = SonicProfile.PROFILE.recipe.units(context(SonicProfile.PROFILE, mem = null)).primary
        assertFalse(small.contains("GOMEMLIMIT"))
        assertTrue(SonicProfile.CACHE_MB >= 6144, "sonicd refuses a cache below 6144")
    }

    @Test
    fun sonic_snapshot_script_downloads_the_flavour_and_imports_it()
    {
        val ctx = context(SonicProfile.PROFILE, "testnet")
        val paths = EvmSnapshotPaths(ctx.root, ctx.bins, EvmSnapshotSentinel.marker(ctx.root), ctx.root.resolve("s.json"), ctx.root.resolve("l.log"), ctx.root.resolve("s.sh"))
        val script = SonicProfile.PROFILE.recipe.snapshotScript(EvmSnapshotRef("sonic", "testnet", "archive"), paths)!!
        assertTrue("https://genesis.soniclabs.com/latest-testnet-archive.g" in script)
        assertTrue("NR==1{print \$1}" in script, "the .md5 file has several lines")
        assertTrue("genesis \"\$FILE\"" in script && "Successfully imported genesis file" in script)
        assertFalse("{{" in script)
        assertTrue(script.startsWith("#!/bin/bash"))
    }

    // --- kaia ---

    @Test
    fun kaia_matches_the_official_endpoint_node_defaults()
    {
        val ctx = context(KaiaProfile.PROFILE, "kairos")
        val unit = KaiaProfile.PROFILE.recipe.units(ctx).primary
        val f = flags(unit)
        assertTrue("--kairos" in f && "--mainnet" !in f)
        assertEquals(listOf("${ctx.dir("chaindata")}"), f["--datadir"])
        assertEquals(listOf("41828"), f["--subport"])
        assertEquals(listOf("klay,eth,net,web3"), f["--rpcapi"])
        assertEquals(listOf("3000"), f["--rpc.concurrencylimit"])
        assertEquals(listOf("41824"), f["--prometheusport"])
        assertTrue("--state.live-pruning" in f && "--prometheus" in f && "--metrics" in f)
        assertEquals("SIGINT", unitSetting(unit, "KillSignal"))
    }

    // --- tempo ---

    @Test
    fun tempo_follows_the_chain_and_pins_every_default_port()
    {
        val ctx = context(TempoProfile.PROFILE, "moderato")
        val unit = TempoProfile.PROFILE.recipe.units(ctx).primary
        assertTrue(command(unit).endsWith("bin/tempo node"), command(unit))
        val f = flags(unit)
        assertTrue("--follow" in f)
        assertEquals(listOf("moderato"), f["--chain"])
        assertEquals(listOf("${ctx.dir("data")}/data"), f["--datadir"])
        assertEquals(listOf("127.0.0.1:42605"), f["--consensus.metrics-address"])
        assertEquals(listOf("42603"), f["--authrpc.port"])
        assertEquals(listOf("127.0.0.1:42604"), f["--metrics"])
        assertTrue("--ipcdisable" in f && f["--color"] == listOf("never"))
    }

    @Test
    fun tempo_snapshot_script_always_passes_a_profile_flag()
    {
        val ctx = context(TempoProfile.PROFILE, "mainnet")
        val paths = EvmSnapshotPaths(ctx.root, ctx.bins, EvmSnapshotSentinel.marker(ctx.root), ctx.root.resolve("s.json"), ctx.root.resolve("l.log"), ctx.root.resolve("s.sh"))
        val script = TempoProfile.PROFILE.recipe.snapshotScript(EvmSnapshotRef("tempo", "mainnet", "minimal"), paths)!!
        assertTrue("PROFILE='minimal'" in script && "CHAIN='mainnet'" in script)
        assertTrue("download --chain \"\$CHAIN\" --datadir \"\$DATA\" --\"\$PROFILE\" -y" in script)
    }

    // --- vana ---

    @Test
    fun vana_hardens_the_official_rpc_limits_and_keeps_the_chain_bootnodes()
    {
        val main = context(VanaProfile.PROFILE, "mainnet")
        val units = VanaProfile.PROFILE.recipe.units(main)
        val geth = flags(units.primary)
        assertEquals(listOf("1480"), geth["--networkid"])
        assertEquals(listOf("5s"), geth["--rpc.evmtimeout"])
        assertEquals(listOf("1000"), geth["--rpc.batch-request-limit"])
        assertEquals(2, geth.getValue("--bootnodes").single().split(',').size)
        val prysm = flags(units.companions.single().second)
        assertEquals(2, prysm.getValue("--bootstrap-node").size)
        assertEquals(listOf("https://rpc.vana.org"), prysm["--checkpoint-sync-url"])
        assertEquals(listOf("127.0.0.1"), prysm["--monitoring-host"])
        assertTrue("--clear-deposit-contract" in prysm)
        val moksha = flags(VanaProfile.PROFILE.recipe.units(context(VanaProfile.PROFILE, "moksha")).companions.single().second)
        assertEquals(3, moksha.getValue("--bootstrap-node").size)
        assertEquals(listOf("14800"), moksha["--chain-id"])
    }

    @Test
    fun vana_mainnet_geth_is_the_raw_patched_binary_and_moksha_the_tarball()
    {
        val r = VanaProfile.PROFILE.recipe
        assertEquals(EvmBinary.Kind.RAW, r.binaries(VanaProfile.PROFILE.env("mainnet")).first().kind)
        assertEquals(EvmBinary.Kind.TAR_MEMBER, r.binaries(VanaProfile.PROFILE.env("moksha")).first().kind)
    }

    // --- berachain ---

    @Test
    fun berachain_pruned_runs_reth_full_and_archive_does_not()
    {
        val pruned = BerachainProfile.PROFILE.recipe.units(context(BerachainProfile.PROFILE, "mainnet"))
        val f = flags(pruned.primary)
        assertTrue("--full" in f && f["--chain"] == listOf("mainnet"))
        assertEquals(listOf("enode://aa@1.2.3.4:30303,enode://bb@5.6.7.8:30303"), f["--bootnodes"])
        assertEquals(listOf("127.0.0.1:41154"), f["--metrics"])
        val cl = pruned.companions.single().second
        assertTrue("Requires=rpcnode-berachain-mainnet.service" in cl)
        assertTrue(command(cl).endsWith("beacond start"))
        val archive = BerachainProfile.PROFILE.recipe.units(
            context(BerachainProfile.PROFILE, "bepolia", EvmLaunchOptions("bepolia", snapshot = "archive")),
        )
        assertFalse("--full" in flags(archive.primary))
        assertEquals(listOf("bepolia"), flags(archive.primary)["--chain"])
    }

    @Test
    fun berachain_bootnodes_come_from_the_regional_list()
    {
        val text = "South Korea\n\nenode://0c5a@34.64.176.79:30303\nenode://b6a3@34.64.181.70:30303\n\nSingapore\n\nenode://5b03@34.87.142.180:30303\n"
        assertEquals("enode://0c5a@34.64.176.79:30303,enode://b6a3@34.64.181.70:30303,enode://5b03@34.87.142.180:30303", BerachainProfile.parseBootnodes(text))
        assertEquals("", BerachainProfile.parseBootnodes("nothing"))
    }

    @Test
    fun berachain_config_patches_use_the_official_sections()
    {
        val ctx = context(BerachainProfile.PROFILE, "mainnet")
        val app = BerachainProfile.appToml(ctx).map { it.section to it.key }
        assertEquals(listOf("beacon-kit.engine" to "rpc-dial-url", "beacon-kit.engine" to "jwt-secret-path", "beacon-kit.kzg" to "trusted-setup-path"), app)
        val cfg = BerachainProfile.configToml(ctx).associate { it.bindingKey to it.value }
        assertEquals("tcp://127.0.0.1:41155", cfg["rpc.laddr"])
        assertEquals("127.0.0.1:41157", cfg["instrumentation.prometheus_listen_addr"])
        assertEquals("tcp://127.0.0.1:41158", cfg["proxy_app"])
    }

    // --- gravity ---

    @Test
    fun gravity_binds_authrpc_and_metrics_to_loopback_and_caps_connections()
    {
        val ctx = context(GravityProfile.PROFILE, "testnet")
        val f = flags(GravityProfile.PROFILE.recipe.units(ctx).primary)
        assertEquals(listOf("127.0.0.1"), f["--authrpc.addr"])
        assertEquals(listOf("127.0.0.1:41614"), f["--metrics"])
        assertEquals(listOf("3000"), f["--rpc.max-connections"])
        assertEquals(listOf("10064"), f["--prune.transactionlookup.distance"])
        assertEquals(listOf(ctx.root.resolve("genesis.json").toString()), f["--chain"])
        assertTrue("--full" in f && "--ipcdisable" in f)
    }

    @Test
    fun gravity_node_config_has_the_network_seeds_and_a_loopback_inspection_service()
    {
        val main = GravityProfile.nodeConfig(context(GravityProfile.PROFILE, "mainnet"))
        assertTrue("mainnet-rpc-p2p-1.gravity.xyz/tcp/6180" in main && "mainnet-rpc-p2p-3.gravity.xyz/tcp/6180" in main)
        assertTrue("address: 127.0.0.1" in main && "port: 41605" in main)
        assertTrue("/ip4/0.0.0.0/tcp/41606" in main)
        assertFalse("{{" in main)
        val test = GravityProfile.nodeConfig(context(GravityProfile.PROFILE, "testnet"))
        assertTrue("testnet-rpc-p2p-1.gravity.xyz/tcp/6195" in test && "mainnet-rpc" !in test)
        // the seeds block sits under the `public` network entry (4 spaces) like the official template
        assertTrue(Regex("""(?m)^    seeds:$""").containsMatchIn(main))
    }

    @Test
    fun sub_second_chain_helper_and_go_memory_limit()
    {
        assertEquals(57, EvmHostFacts.goMemLimitGiB(64, 90))
        assertEquals(1, EvmHostFacts.goMemLimitGiB(1, 10))
    }
}
