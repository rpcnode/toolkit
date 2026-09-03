package rpcnode.toolkit.chains.sui.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
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
        assertEquals(12.0, p!!.pct, 0.01)
        assertTrue(p.detail.contains("12"))
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
        assertTrue(yaml.contains("json-rpc-address: \"127.0.0.1:9000\""))
        assertTrue(yaml.contains("metrics-address: \"127.0.0.1:9184\""))
        assertTrue(yaml.contains("listen-address: \"0.0.0.0:8084\""))
        assertTrue(yaml.contains("num-epochs-to-retain: ${SuiUnitBodies.NUM_EPOCHS_TO_RETAIN}"))
        assertTrue(yaml.contains("enable-event-processing: true"))
        assertTrue(yaml.contains("concurrency: ${SuiUnitBodies.ARCHIVE_CONCURRENCY}"))
        assertTrue(yaml.contains("genesis-file-location: \"/data/genesis.blob\""))
    }
}
