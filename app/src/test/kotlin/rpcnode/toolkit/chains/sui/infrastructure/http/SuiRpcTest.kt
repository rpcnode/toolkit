package rpcnode.toolkit.chains.sui.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.sui.infrastructure.SuiClusters
import rpcnode.toolkit.chains.sui.infrastructure.SuiUnitBodies

class SuiRpcTest
{
    @Test
    fun parseCheckpoint_reads_json_rpc_result()
    {
        assertEquals(
            42L,
            SuiRpc.parseCheckpoint("""{"jsonrpc":"2.0","id":1,"result":"42"}"""),
        )
        assertEquals(
            100L,
            SuiRpc.parseCheckpoint("""{"jsonrpc":"2.0","id":1,"result":100}"""),
        )
        assertNull(
            SuiRpc.parseCheckpoint(
                """{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"Method not found"}}""",
            ),
        )
    }

    @Test
    fun parseCheckpointHeader_reads_health_header()
    {
        assertEquals(395081535L, SuiRpc.parseCheckpointHeader(" 395081535 "))
        assertNull(SuiRpc.parseCheckpointHeader(null))
        assertNull(SuiRpc.parseCheckpointHeader("up"))
        assertNull(SuiRpc.parseCheckpointHeader("-1"))
    }

    @Test
    fun parseSyncedCheckpoint_from_prometheus()
    {
        val body = """
            # HELP highest_synced_checkpoint
            highest_synced_checkpoint 12345
            highest_known_checkpoint 12350
        """.trimIndent()
        assertEquals(12345L, SuiRpc.parseSyncedCheckpoint(body))
    }

    @Test
    fun parseGraphQlCheckpoint()
    {
        assertEquals(
            99L,
            SuiRpc.parseGraphQlCheckpoint(
                """{"data":{"checkpoint":{"sequenceNumber":"99"}}}""",
            ),
        )
    }

    @Test
    fun formal_progress_parses_files_done()
    {
        val p = SuiFormalSnapshotProgress.parseLog("Downloading… 12 out of 100 files done")
        assertTrue(p!!.pct > 0.0)
        assertTrue(p.detail.contains("12"))
    }

    @Test
    fun formal_progress_weights_sui_tool_bars()
    {
        val text = """
            [00:10:00] 900/900 (Checkpoint summary sync is complete)
            [00:10:00] 10 out of 10 missing .ref files done (file: x)
            [00:10:00] 10 out of 10 ref files checksummed (Bucket: 1, Part: 1)
            [00:12:00] 50 out of 100 .obj files done (Download speed: 91.25 MiB/s)
        """.trimIndent()
        val p = SuiFormalSnapshotProgress.parseLog(text)!!
        // summaries 10 + ref 5 + checksum 5 + obj 65 * 0.5
        assertEquals(52.5, p.pct, 0.01)
        assertTrue(p.detail.startsWith("Downloading .obj files · 50 / 100"), p.detail)
        assertTrue(p.detail.contains("91.2 MiB/s") || p.detail.contains("91.3 MiB/s"), p.detail)
    }
}

class SuiSnapshotResolverTest
{
    @Test
    fun resolve_and_parse_formal_r2() = runTest {
        val resolver = SuiSnapshotResolver()
        val archive = resolver.resolve(EnvId.MAINNET, "formal")
        assertEquals("formal-r2://mainnet", archive!!.url)
        assertTrue(SuiSnapshotResolver.isOfficialUrl(archive.url))
        assertEquals("mainnet", SuiSnapshotResolver.parse(archive.url)!!.env)
    }
}

class SuiUnitBodiesTest
{
    @Test
    fun fullnode_yaml_pins_capacity_and_ports()
    {
        val yaml = SuiUnitBodies.fullnodeYaml(
            env = "mainnet",
            dbPath = "/data/db",
            metricsPort = 9184,
            rpcPort = 9000,
            p2pPort = 8084,
            genesisPath = "/data/genesis.blob",
            archiveUrl = "https://checkpoints.mainnet.sui.io",
        )
        assertTrue(yaml.contains("json-rpc-address: \"0.0.0.0:9000\""))
        assertTrue(yaml.contains("metrics-address: \"0.0.0.0:9184\""))
        assertTrue(yaml.contains("listen-address: \"0.0.0.0:8084\""))
        assertTrue(yaml.contains("num-epochs-to-retain: ${SuiUnitBodies.NUM_EPOCHS_TO_RETAIN}"))
        assertTrue(yaml.contains("enable-event-processing: true"))
        assertTrue(yaml.contains("concurrency: ${SuiUnitBodies.ARCHIVE_CONCURRENCY}"))
        assertTrue(yaml.contains("genesis-file-location: \"/data/genesis.blob\""))
    }

    @Test
    fun fullnode_yaml_names_the_state_sync_seed_peers_of_its_env()
    {
        fun yaml(env: String) = SuiUnitBodies.fullnodeYaml(
            env = env, dbPath = "/d", metricsPort = 1, rpcPort = 2, p2pPort = 3, genesisPath = "/g", archiveUrl = "https://a",
        )
        val main = yaml("mainnet")
        assertTrue(main.contains("  seed-peers:\n    - address: \"/dns/mel-00.mainnet.sui.io/udp/8084\"\n      peer-id: \"d32b55bdf1737ec415df8c88b3bf91e194b59ee3127e3f38ea46fd88ba2e7849\""))
        assertEquals(10, Regex("peer-id:").findAll(main).count())
        val test = yaml("testnet")
        assertEquals(7, Regex("peer-id:").findAll(test).count())
        assertTrue(test.contains("/dns/ewr-tnt-ssfn-00.testnet.sui.io/udp/8084") && !test.contains("mainnet.sui.io"))
        // seed-peers sits inside p2p-config, before the next top-level key
        assertTrue(main.indexOf("p2p-config:") < main.indexOf("seed-peers:") && main.indexOf("seed-peers:") < main.indexOf("genesis:"))
        for (cluster in listOf(SuiClusters.lookup("mainnet"), SuiClusters.lookup("testnet")))
        {
            assertTrue(cluster.seedPeers.all { it.peerId.matches(Regex("[0-9a-f]{64}")) && it.address.matches(Regex("/dns/[a-z0-9.\\-]+/udp/\\d+")) })
            assertEquals(cluster.seedPeers.size, cluster.seedPeers.map { it.peerId }.toSet().size)
        }
    }
}
