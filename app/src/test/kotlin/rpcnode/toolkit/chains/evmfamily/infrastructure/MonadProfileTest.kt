package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmTestSupport.allUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmTestSupport.flags
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmTestSupport.unitSetting
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.MonadProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.MonadHost

class MonadProfileTest
{
    private val profile = MonadProfile.PROFILE
    private val device = "/dev/disk/by-partuuid/1111-2222"

    private fun units(env: String) = profile.recipe.units(
        EvmTestSupport.context(profile, env, EvmLaunchOptions(env = env, extra = mapOf(MonadProfile.DEVICE_OPTION to device))),
    )

    @Test
    fun three_units_a_hugepages_oneshot_and_the_cruft_timer_start_companions_first()
    {
        val u = units("mainnet")
        assertEquals(
            listOf(
                "rpcnode-monad-hugepages-mainnet.service",
                "rpcnode-monad-bft-mainnet.service",
                "rpcnode-monad-rpc-mainnet.service",
                "rpcnode-monad-cruft-mainnet.service",
                "rpcnode-monad-cruft-mainnet.timer",
            ),
            u.companions.map { it.first },
        )
        val timer = u.companions.last().second
        assertTrue(timer.contains("[Timer]") && timer.contains("OnCalendar=hourly"))
        assertFalse(timer.contains("Unit="), "the timer fires the service of the same name")
        assertTrue(u.companions[3].second.contains("Type=oneshot") && u.companions[3].second.contains("/bin/rpcnode-monad-cruft.sh"))
        assertTrue(u.startCompanionsFirst)
        val pages = u.companions.first().second
        assertTrue(pages.contains("Type=oneshot") && pages.contains("RemainAfterExit=yes"))
        assertTrue(pages.contains("hugepages-1048576kB/nr_hugepages") && pages.contains("hugepages-2048kB/nr_hugepages"))
    }

    @Test
    fun execution_unit_matches_the_packaged_unit_with_paths_moved_under_node_dir()
    {
        val body = units("mainnet").primary
        val f = flags(body)
        assertEquals(listOf("monad_mainnet"), f["--chain"])
        assertEquals(listOf(device), f["--db"])
        assertTrue(f.getValue("--block_db").single().endsWith("/state/ledger"))
        assertTrue(f.getValue("--statesync").single().endsWith("/state/statesync.sock"))
        assertEquals(listOf("1"), f["--sq_thread_cpu"])
        assertTrue(!f.containsKey("--db-stats-file"), "not in the 0.16.x packaged unit")
        assertEquals("no", unitSetting(body, "Restart"))
        assertEquals("1-7", unitSetting(body, "AllowedCPUs"))
        assertEquals("1,2,3,4,5,6,7", unitSetting(body, "CPUAffinity"))
        assertEquals("infinity", unitSetting(body, "LimitMEMLOCK"))
        assertEquals("1048576", unitSetting(body, "LimitNOFILE"))
        assertTrue(body.contains("Environment=LD_LIBRARY_PATH="))
        assertTrue(body.contains("Requires=rpcnode-monad-hugepages-mainnet.service"))
        assertFalse(body.contains("/home/monad") || body.contains("/opt/monad") || body.contains("/dev/triedb"))
    }

    @Test
    fun consensus_unit_reads_its_password_from_the_private_env_file()
    {
        val bft = units("testnet").companions.first { it.first.contains("-bft-") }.second
        val f = flags(bft)
        assertEquals(listOf("\"\${KEYSTORE_PASSWORD}\""), f["--keystore-password"])
        assertEquals(listOf("8"), f["--statesync-sq-thread-cpu"])
        assertEquals(listOf(device), f["--triedb-path"])
        assertTrue(bft.contains("EnvironmentFile=") && bft.contains("/.toolkit/monad.env"))
        assertTrue(bft.contains("Environment=REMOTE_FORKPOINT_URL=https://bucket.monadinfra.com/forkpoint/testnet/forkpoint.toml"))
        assertTrue(bft.contains("Environment=REMOTE_VALIDATORS_URL=https://bucket.monadinfra.com/validators/testnet/validators.toml"))
        assertEquals("no", unitSetting(bft, "Restart"))
        assertEquals("8-11", unitSetting(bft, "AllowedCPUs"))
        assertFalse(bft.contains("otel"), "no collector is installed")
        assertTrue(!f.containsKey("--triedb-stats-path"))
    }

    @Test
    fun rpc_unit_uses_the_catalog_ports_and_restarts_always()
    {
        val rpc = units("mainnet").companions.first { it.first.contains("-rpc-") }.second
        val f = flags(rpc)
        assertEquals(listOf("42020"), f["--rpc-port"])
        assertEquals(listOf("42021"), f["--ws-port"])
        assertEquals(listOf("0.0.0.0"), f["--rpc-addr"])
        assertTrue(f.containsKey("--allow-unprotected-txs"))
        assertEquals("always", unitSetting(rpc, "Restart"))
        assertEquals("12-15", unitSetting(rpc, "AllowedCPUs"))
    }

    @Test
    fun testnet_ports_and_chain_follow_the_profile()
    {
        val rpc = units("testnet").companions.first { it.first.contains("-rpc-") }.second
        assertEquals(listOf("42030"), flags(rpc)["--rpc-port"])
        assertEquals(listOf("monad_testnet"), flags(units("testnet").primary)["--chain"])
        assertEquals(10143L, profile.env("testnet").chainId)
        assertEquals(143L, profile.env("mainnet").chainId)
    }

    @Test
    fun every_unit_renders_without_placeholders()
    {
        for (env in listOf("mainnet", "testnet"))
        {
            val u = units(env)
            for (body in listOf(u.primary) + u.companions.map { it.second }) assertFalse(body.contains("{{"), body)
        }
    }

    @Test
    fun all_binaries_come_from_the_one_deb_and_the_first_is_the_primary_entry()
    {
        val bins = profile.recipe.binaries(profile.env("mainnet"))
        assertEquals("monad", bins.first().name)
        assertTrue(bins.all { it.kind == EvmBinary.Kind.DEB_BUNDLE && it.synced == "monad-amd64.deb" })
        assertEquals(
            setOf("monad", "monad-node", "monad-rpc", "monad-mpt", "monad-cli", "monad-keystore", "monad-sign-name-record"),
            bins.map { it.name }.toSet(),
        )
    }

    @Test
    fun the_tribedb_device_and_ip_travel_as_launch_options_and_into_the_snapshot_url()
    {
        val opts = EvmLaunchOptions.extraFrom("""{"triedb_device":"$device","public_ip":"","other":"x"}""", profile.optionKeys)
        assertEquals(mapOf("triedb_device" to device), opts)
        val args = EvmLaunchOptions(env = "mainnet", extra = opts).toArgs()
        assertEquals(opts, EvmLaunchOptions.parse(args).extra)
        val url = EvmSnapshotSentinel.url(EvmSnapshotRef("monad", "mainnet", "full"))
        val withDevice = EvmSnapshotSentinel.withParams(url, opts)
        assertEquals(device, EvmSnapshotSentinel.parse(withDevice)!!.param("triedb_device"))
        assertEquals(url, EvmSnapshotSentinel.withParams(url, emptyMap()))
        assertEquals("https://x/y", EvmSnapshotSentinel.withParams("https://x/y", opts))
    }

    @Test
    fun a_snapshot_without_a_usable_device_is_refused_before_a_script_exists()
    {
        val root = Files.createTempDirectory("monad-snap")
        val paths = EvmSnapshotPaths(root, emptyMap(), root.resolve("m"), root.resolve("s"), root.resolve("l"), root.resolve("x.sh"))
        val noDevice = assertFailsWith<IllegalStateException> {
            profile.recipe.snapshotScript(EvmSnapshotRef("monad", "mainnet", "full"), paths)
        }
        assertTrue(noDevice.message!!.contains("TrieDB block device"), noDevice.message)
        val notABlock = assertFailsWith<IllegalStateException> {
            profile.recipe.snapshotScript(EvmSnapshotRef("monad", "mainnet", "full", mapOf("triedb_device" to "/dev/null/x")), paths)
        }
        assertTrue(notABlock.message!!.contains("TrieDB device"), notABlock.message)
    }

    @Test
    fun device_paths_must_be_plain_dev_paths()
    {
        assertTrue(MonadHost.validDevicePath("/dev/nvme1n1p1"))
        assertTrue(MonadHost.validDevicePath("/dev/disk/by-partuuid/1111-2222"))
        assertFalse(MonadHost.validDevicePath("/etc/passwd"))
        assertFalse(MonadHost.validDevicePath("/dev/../etc/passwd"))
        assertFalse(MonadHost.validDevicePath("/dev/sda; rm -rf /"))
        assertFalse(MonadHost.validDevicePath("/dev/sda 1"))
        assertNull(MonadHost.checkCpus(16))
        assertTrue(MonadHost.checkCpus(8)!!.contains("16"))
    }
}
