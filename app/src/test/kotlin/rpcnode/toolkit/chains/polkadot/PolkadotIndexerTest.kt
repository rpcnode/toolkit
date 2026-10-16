package rpcnode.toolkit.chains.polkadot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.nonevm.infrastructure.CatalogFixtures
import rpcnode.toolkit.chains.polkadot.infrastructure.PolkadotIndexer
import rpcnode.toolkit.chains.polkadot.infrastructure.PolkadotIndexerDiagnostics
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpecs
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest

class PolkadotIndexerTest
{
    private val spec = SubstrateSpecs.POLKADOT

    @Test
    fun indexer_bindings_equal_the_environment_of_the_process()
    {
        for (env in spec.envs)
        {
            val ports = spec.ports(env)
            val applied = PolkadotIndexer.environment(ports.rpc, ports.indexer)
            val gated = CatalogFixtures.bindingsFor("polkadot", env.id).filter { it.whenOption == "indexer" && it.whenOptionValue == "rest-api" }
            assertTrue(gated.isNotEmpty())
            for (b in gated)
            {
                val want = if (b.source == "catalog_port") CatalogFixtures.ports("polkadot", env.id)[b.role].toString() else b.value
                assertEquals(want, applied[b.key], "${env.id} ${b.key}")
            }
            // every applied variable is visible, except the loopback node URL derived from the node's own port
            val bound = gated.map { it.key }.toSet()
            assertEquals(emptySet(), applied.keys - bound - "SAS_SUBSTRATE_URL", env.id)
            assertEquals("ws://127.0.0.1:${ports.rpc}", applied["SAS_SUBSTRATE_URL"])
        }
    }

    @Test
    fun metrics_stay_off_so_the_public_port_does_not_publish_them()
    {
        assertEquals("false", PolkadotIndexer.environment(42200, 42205)["SAS_METRICS_ENABLED"])
    }

    @Test
    fun script_and_unit_render_and_the_script_parses()
    {
        val env = spec.env("mainnet")
        val script = PolkadotIndexer.script(env, spec.ports(env), "/n", "/n/.toolkit", "/n/indexer", "/n/logs/polkadot-indexer.log")
        assertTrue(!script.contains("{{"), script)
        assertTrue(script.contains("export SAS_EXPRESS_PORT='42205'"))
        assertTrue(script.contains("TAG=\"\${RPCNODE_POLKADOT_API_TAG:-v0.3.2}\""))
        assertTrue(script.contains("RUST_VERSION=1.90.0"))
        val tmp = kotlin.io.path.createTempFile("indexer", ".sh").toFile().also { it.writeText(script); it.deleteOnExit() }
        val p = ProcessBuilder("bash", "-n", tmp.path).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), out)

        val (name, unit) = PolkadotIndexer.companion(env, "/n")
        assertEquals("rpcnode-polkadot-mainnet-indexer.service", name)
        assertTrue(!unit.contains("{{"), unit)
        assertTrue(unit.contains("ExecStart=/n/bin/rpcnode-polkadot-indexer.sh"))
        assertTrue(unit.contains("After=network-online.target rpcnode-polkadot-mainnet.service"))
    }

    @Test
    fun only_the_rest_api_option_turns_it_on()
    {
        assertTrue(PolkadotIndexer.enabled("rest-api"))
        assertTrue(!PolkadotIndexer.enabled(null))
        assertTrue(!PolkadotIndexer.enabled("none"))
        assertTrue(!PolkadotIndexer.enabled("sidecar"))
    }

    @Test
    fun diagnostics_cover_not_installed_building_running_and_lagging()
    {
        val req = DiagnosticRequest(id = "polkadot_indexer", port = 42205)
        fun run(exists: Boolean = true, state: String = "active", get: (Int, String) -> String? = { _, _ -> null }, head: Long? = 100) =
            PolkadotIndexerDiagnostics.check(req, { exists }, { state }, get, { head })
        assertEquals("info", run(exists = false).level)
        assertEquals("info", run(state = "activating").level)
        assertEquals("error", run(state = "failed").level)
        assertEquals("warn", run(state = "active").level)
        val up: (Int, String) -> String? = { _, path -> if (path == "/v1/health") """{"status":"ok"}""" else """{"number":"90","hash":"0x1"}""" }
        assertEquals("ok", run(get = up, head = 100).level)
        assertEquals("warn", run(get = up, head = 500).level)
        assertEquals("unknown", PolkadotIndexerDiagnostics.check(DiagnosticRequest("polkadot_indexer", 1)).level)
    }
}
