package rpcnode.toolkit.chains.cardano.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CardanoUnitBodiesTest
{
    private val upstream = """
        {
          "AlonzoGenesisFile": "alonzo-genesis.json",
          "LedgerDB": {"Backend": "V2LSM", "NumOfDiskSnapshots": 2},
          "TargetNumberOfRootPeers": 100,
          "TraceOptions": {
            "": {"severity": "Notice", "detail": "DNormal", "backends": ["Stdout MachineFormat", "PrometheusSimple suffix 0.0.0.0 12798", "EKGBackend"]},
            "ChainDB": {"severity": "Info"}
          }
        }
    """.trimIndent()

    @Test
    fun `config applies the production settings and keeps the rest`()
    {
        val out = Json.parseToJsonElement(CardanoUnitBodies.configJson(upstream, 41214)).jsonObject
        assertEquals("alonzo-genesis.json", out["AlonzoGenesisFile"]!!.jsonPrimitive.content)
        assertEquals("V2InMemory", out["LedgerDB"]!!.jsonObject["Backend"]!!.jsonPrimitive.content)
        assertEquals("2", out["LedgerDB"]!!.jsonObject["NumOfDiskSnapshots"]!!.jsonPrimitive.content)
        assertEquals("60", out["TargetNumberOfRootPeers"]!!.jsonPrimitive.content)
        assertEquals("150", out["TargetNumberOfKnownPeers"]!!.jsonPrimitive.content)
        assertEquals("512", out["AcceptedConnectionsLimit"]!!.jsonObject["hardLimit"]!!.jsonPrimitive.content)
        val backends = out["TraceOptions"]!!.jsonObject[""]!!.jsonObject["backends"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("Stdout HumanFormatUncoloured", "PrometheusSimple suffix 127.0.0.1 41214", "EKGBackend"), backends)
        assertTrue(out["TraceOptions"]!!.jsonObject.containsKey("ChainDB"))
    }

    @Test
    fun `missing backends are appended`()
    {
        val minimal = """{"TraceOptions":{"":{"backends":[]}}}"""
        val out = Json.parseToJsonElement(CardanoUnitBodies.configJson(minimal, 41224)).jsonObject
        val backends = out["TraceOptions"]!!.jsonObject[""]!!.jsonObject["backends"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("PrometheusSimple suffix 127.0.0.1 41224", "Stdout HumanFormatUncoloured"), backends)
    }

    @Test
    fun `args carry ports and paths`()
    {
        val args = CardanoUnitBodies.args("/n", "/d/db", "/n/node.socket", 41212)
        assertEquals("--port", args[args.indexOf("41212") - 1])
        assertTrue(args.containsAll(listOf("--topology", "/n/config/topology.json", "--database-path", "/d/db", "--host-addr", "0.0.0.0")))
        assertEquals(listOf("run", "+RTS", "-N2", "-RTS"), args.take(4))
    }

    @Test
    fun `unit renders every placeholder`()
    {
        val body = CardanoUnitBodies.unit("preprod", "/n", "/n/node.socket", "/n/bin/cardano-node run", "/n/logs/cardano.log")
        assertTrue(!body.contains("{{"), body)
        assertTrue(body.contains("LimitNOFILE=1048576"))
        assertTrue(body.contains("TimeoutStopSec=600"))
        assertTrue(body.contains("ExecStart=/n/bin/cardano-node run"))
    }

    @Test
    fun `ports are unique per env`()
    {
        val all = listOf("mainnet", "preprod", "preview").flatMap { CardanoPortTable.forEnv(it).let { p -> listOf(p.p2p, p.metrics, p.ogmios, p.dbSyncMetrics) } }
        assertEquals(all.size, all.toSet().size)
        assertEquals(41212, CardanoPortTable.forEnv("mainnet").p2p)
        assertEquals(41234, CardanoPortTable.forEnv("preview").metrics)
        assertEquals(41210, CardanoPortTable.forEnv("mainnet").ogmios)
        assertEquals(41237, CardanoPortTable.forEnv("preview").dbSyncMetrics)
    }
}
