package rpcnode.toolkit.chains.etc.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.infrastructure.host.UnitFlagOverrides

class EtcUnitBodiesTest
{
    private fun unit(env: String, archive: Boolean = false) = EtcUnitBodies.unit(
        env = env,
        nodeDir = "/n",
        bin = "/n/bin/geth",
        datadir = "/n/datadir",
        httpPort = EtcPortTable.forEnv(env).http,
        wsPort = EtcPortTable.forEnv(env).ws,
        p2pPort = EtcPortTable.forEnv(env).p2p,
        archive = archive,
        logFile = "/n/logs/node.out",
    )

    @Test
    fun mainnet_full_unit_has_the_production_flags()
    {
        val u = unit("mainnet")
        for (flag in listOf(
            "--classic", "--syncmode snap", "--gcmode full", "--cache 4096", "--http.port 41390", "--ws.port 41391", "--port 41392",
            "--http.addr 0.0.0.0", "--http.api eth,net,web3", "--http.vhosts *", "--ws.addr 0.0.0.0", "--ws.api eth,net,web3", "--ws.origins *", "--maxpeers 50",
            "--history.transactions 0", "--mess=true", "--rpc.gascap 50000000", "--rpc.evmtimeout 5s",
            "--rpc.batch-request-limit 1000", "--rpc.batch-response-max-size 25000000", "--rpc.txfeecap 1",
            "--txpool.globalslots 5120", "--txpool.globalqueue 1024", "--ipcdisable", "LimitNOFILE=1048576",
            "TimeoutStopSec=300", "WantedBy=multi-user.target",
        ))
        {
            assertTrue(flag in u, "missing $flag in\n$u")
        }
        for (bad in listOf("--authrpc", "--graphql", "--http.corsdomain", "--rpc.allow-unprotected-txs", "debug", "User=", "/opt/"))
        {
            assertFalse(bad in u, "unexpected $bad")
        }
        assertFalse("{{" in u)
        val exec = u.lines().dropWhile { !it.startsWith("ExecStart=") }.takeWhile { !it.startsWith("Restart=") }
        assertTrue(exec.dropLast(1).all { it.trimEnd().endsWith("\\") }, exec.joinToString("\n"))
        assertFalse(exec.last().trimEnd().endsWith("\\"))
    }

    @Test
    fun archive_mordor_unit()
    {
        val u = unit("mordor", archive = true)
        for (flag in listOf("--mordor", "--syncmode full", "--gcmode archive", "--cache 1024", "--http.port 41400", "--port 41402"))
        {
            assertTrue(flag in u, "missing $flag")
        }
    }

    @Test
    fun catalog_ports_and_bindings_match_the_code()
    {
        val programs = YamlClientProgramCatalog().all().filter { it.network == NetworkId.ETC }
        assertEquals(setOf("mainnet", "mordor"), programs.map { it.env.value }.toSet())
        for (p in programs)
        {
            val ports = EtcPortTable.forEnv(p.env.value)
            val byRole = p.ports.associate { it.role to it.port }
            assertEquals(ports.http, byRole["http"], "${p.env.value} http")
            assertEquals(ports.ws, byRole["ws"], "${p.env.value} ws")
            assertEquals(ports.p2p, byRole["p2p"], "${p.env.value} p2p")
            assertEquals("core-geth", p.programId)
            assertEquals(listOf("core-geth-linux.zip"), p.artifacts.map { it.name })
        }
        val facts = YamlNetworkFactsRepository().factsFor(NetworkId.ETC)!!
        assertEquals("flags", facts.clientConfig!!.format)
        assertEquals("core-geth", facts.clientConfig!!.program)
        val literals = facts.clientConfig!!.bindings.filter { it.source == "literal" }.groupBy { it.path }
        fun literal(key: String) = literals[key]?.firstOrNull()?.value
        assertEquals(EtcUnitBodies.RPC_GASCAP.toString(), literal("rpc-gascap"))
        assertEquals(EtcUnitBodies.MESS.toString(), literal("mess"))
        assertEquals(EtcUnitBodies.HTTP_API, literal("http-api"))
        assertEquals(EtcUnitBodies.WS_API, literal("ws-api"))
        assertEquals(EtcUnitBodies.WS_ORIGINS, literal("ws-origins"))
        assertEquals(EtcUnitBodies.NODE_NOFILE.toString(), literal("LimitNOFILE"))
        assertEquals("snapshot never", "snapshot " + facts.envs.first().snapshot)
    }

    @Test
    fun flag_overrides_replace_unit_flags_but_not_locked_ones()
    {
        val out = UnitFlagOverrides.apply(
            unit("mainnet"),
            "http.api=eth,net\nmess=false\nrpc.evmtimeout=3s\nhttp.port=1\ndatadir=/x",
        )
        assertTrue("--http.api eth,net" in out && "--http.api eth,net,web3" !in out, out)
        assertTrue("--mess=false" in out && "--mess=true" !in out, out)
        assertTrue("--rpc.evmtimeout 3s" in out, out)
        assertTrue("--http.port 41390" in out && "--datadir /n/datadir" in out, out)
    }
}
