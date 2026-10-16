package rpcnode.toolkit.chains.stellar.infrastructure

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository

class StellarUnitBodiesTest
{
    private val root = Path.of("/data/rpcnode/stellar/mainnet")

    private fun toml(env: String, history: String) =
        StellarUnitBodies.rpcToml(
            StellarClusters.lookup(env),
            StellarPortTable.forEnv(env),
            StellarHistory.parse(history),
            root,
            root,
        )

    @Test
    fun mainnet_week_backfills_from_the_data_lake()
    {
        val t = toml("mainnet", "week")
        for (line in listOf(
            """ENDPOINT = "0.0.0.0:42560"""", """ADMIN_ENDPOINT = "127.0.0.1:42564"""",
            "STELLAR_CAPTIVE_CORE_HTTP_PORT = 0", "STELLAR_CAPTIVE_CORE_HTTP_QUERY_PORT = 42563",
            "HISTORY_RETENTION_WINDOW = 120960", "BACKFILL = true", "SERVE_LEDGERS_FROM_DATASTORE = true",
            """destination_bucket_path = "aws-public-blockchain/v1.1/stellar/ledgers/pubnet"""",
            "PREFLIGHT_ENABLE_DEBUG = false", "REQUEST_BACKLOG_GLOBAL_QUEUE_LIMIT = 10000",
            """CAPTIVE_CORE_STORAGE_PATH = "/data/rpcnode/stellar/mainnet/captive-core"""",
            """DB_PATH = "/data/rpcnode/stellar/mainnet/stellar_rpc.sqlite"""",
        ))
        {
            assertTrue(line in t, "missing $line in\n$t")
        }
        assertTrue(t.indexOf("[datastore_config]") > t.indexOf("LOG_FORMAT"), "tables must come last")
    }

    @Test
    fun testnet_has_no_backfill_and_forward_never_backfills()
    {
        val t = toml("testnet", "week")
        assertTrue("""ENDPOINT = "0.0.0.0:42570"""" in t)
        assertTrue("core_testnet_001/" in t && "Test SDF Network ; September 2015" in t)
        assertFalse("BACKFILL" in t || "[datastore_config]" in t)

        val f = toml("mainnet", "forward")
        assertTrue("HISTORY_RETENTION_WINDOW = 4294967295" in f)
        assertFalse("BACKFILL" in f)
    }

    @Test
    fun unit_and_heal_script_render()
    {
        val u = StellarUnitBodies.unit("mainnet", "/n", "/n/bin/stellar-rpc --config-path /n/stellar-rpc.toml", "/n/bin/rpcnode-stellar-heal.sh", "/n/logs/stellar-rpc.log")
        assertFalse("{{" in u)
        for (line in listOf(
            "ExecStartPre=-/n/bin/rpcnode-stellar-heal.sh", "ExecStart=/n/bin/stellar-rpc --config-path /n/stellar-rpc.toml",
            "KillMode=control-group", "LimitNOFILE=1048576", "Environment=TMPDIR=/n/tmp",
        ))
        {
            assertTrue(line in u, "missing $line")
        }
        val h = StellarUnitBodies.healScript("/n/logs/stellar-rpc.log", "/n/.toolkit", "/n/captive-core")
        assertFalse("{{" in h)
        assertTrue("rm -rf \"\$CORE_STORAGE\"" in h && "1800" in h)
    }

    @Test
    fun catalog_ports_and_bindings_match_the_code()
    {
        val programs = YamlClientProgramCatalog().all().filter { it.network == NetworkId.STELLAR }
        assertEquals(setOf("mainnet", "testnet"), programs.map { it.env.value }.toSet())
        for (p in programs)
        {
            val ports = StellarPortTable.forEnv(p.env.value)
            val byRole = p.ports.associate { it.role to it.port }
            assertEquals(ports.http, byRole["http"])
            assertEquals(ports.p2p, byRole["p2p"])
            assertEquals(ports.coreQuery, byRole["core_query"])
            assertEquals(ports.admin, byRole["admin"])
            assertEquals("stellar-rpc", p.programId)
        }
        val facts = YamlNetworkFactsRepository().factsFor(NetworkId.STELLAR)!!
        assertEquals("flags", facts.clientConfig!!.format)
        val literals = facts.clientConfig!!.bindings.filter { it.source == "literal" }.associate { it.path to it.value }
        assertEquals(StellarUnitBodies.ENDPOINT_HOST, literals["endpoint-host"])
        assertEquals(StellarUnitBodies.ADMIN_HOST, literals["admin-host"])
        assertEquals(StellarUnitBodies.CAPTIVE_CORE_HTTP_PORT.toString(), literals["captive-core-http-port"])
        assertEquals(StellarUnitBodies.PREFLIGHT_WORKER_COUNT.toString(), literals["preflight-worker-count"])
        assertEquals(StellarUnitBodies.PREFLIGHT_ENABLE_DEBUG.toString(), literals["preflight-enable-debug"])
        assertEquals(StellarUnitBodies.REQUEST_BACKLOG_GLOBAL_QUEUE_LIMIT.toString(), literals["request-backlog-global-queue-limit"])
        assertEquals(StellarUnitBodies.MAX_REQUEST_EXECUTION_DURATION, literals["max-request-execution-duration"])
        assertEquals(StellarUnitBodies.NODE_NOFILE.toString(), literals["LimitNOFILE"])
        assertTrue(facts.envs.all { it.snapshot == "never" })
    }
}
