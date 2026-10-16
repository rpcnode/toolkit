package rpcnode.toolkit.chains.xrpl.infrastructure

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrplCfgBindTest
{
    private fun cfg(env: String) = XrplUnitBodies.cfg(
        cluster = XrplClusters.lookup(env),
        etc = Path.of("/n/etc"),
        data = Path.of("/n/data"),
        ports = XrplPortTable.forEnv(env),
        policy = XrplHistory.DEFAULT,
        hasLedger = false,
    )

    /** The text of one `[section]` up to the next blank line. */
    private fun section(cfg: String, name: String): String =
        cfg.substringAfter("[$name]\n").substringBefore("\n\n")

    @Test
    fun json_rpc_and_public_websocket_listen_on_every_interface_but_only_localhost_is_admin()
    {
        for (env in listOf("mainnet", "testnet"))
        {
            val cfg = cfg(env)
            val rpc = section(cfg, "port_rpc")
            assertTrue(rpc.contains("ip = 0.0.0.0"), "$env json-rpc must be reachable from other hosts")
            assertTrue(rpc.contains("admin = 127.0.0.1"), "admin rights only for clients on this host")
            assertTrue(rpc.contains("port = ${XrplPortTable.forEnv(env).http}"))
            assertTrue(section(cfg, "port_ws_public").contains("ip = 0.0.0.0"))
            assertFalse(section(cfg, "port_ws_public").contains("admin"), "the public websocket has no admin")
            assertTrue(cfg.contains("[server]\nport_rpc\n"))
        }
    }

    @Test
    fun the_admin_websocket_stays_on_loopback_and_grpc_listens_everywhere_behind_a_secure_gateway()
    {
        val cfg = cfg("testnet")
        assertTrue(section(cfg, "port_ws_admin_local").contains("ip = 127.0.0.1"))
        assertTrue(section(cfg, "port_grpc").contains("ip = 0.0.0.0"))
        assertTrue(section(cfg, "port_grpc").contains("secure_gateway = 127.0.0.1"))
        assertTrue(section(cfg, "port_peer").contains("ip = 0.0.0.0"))
    }
}
