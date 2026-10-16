package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MonadNodeTomlTest
{
    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("monad/$name")) { "missing $name" }.readBytes().toString(Charsets.UTF_8)

    private fun patched(env: String, signed: Map<String, String>): String
    {
        val template = resource("$env-full-node.toml")
        val peer = LinkedHashMap(MonadNodeToml.peerSection(env, 42022, 42023, 42024))
        peer.putAll(signed)
        return MonadNodeToml.patch(
            template,
            "full_rpcnode-abc",
            mapOf("peer_discovery" to peer, "network" to MonadNodeToml.networkSection(env, 42022, 42023, 42024)),
        )
    }

    @Test
    fun mainnet_gets_its_name_ports_and_signed_record_and_keeps_every_vendor_peer()
    {
        val out = patched(
            "mainnet",
            mapOf(
                "self_address" to "\"203.0.113.9:42022\"",
                "self_record_seq_num" to "1",
                "self_name_record_sig" to "\"abcd\"",
            ),
        )
        assertTrue(out.contains("node_name = \"full_rpcnode-abc\""))
        assertEquals("203.0.113.9:42022", MonadNodeToml.peerValue(out, "self_address"))
        assertEquals("42023", MonadNodeToml.peerValue(out, "self_auth_port"))
        assertEquals("1", MonadNodeToml.peerValue(out, "self_record_seq_num"))
        assertEquals("abcd", MonadNodeToml.peerValue(out, "self_name_record_sig"))
        assertTrue(out.contains("bind_address_port = 42022"))
        assertTrue(out.contains("authenticated_bind_address_port = 42023"))
        assertFalse(out.contains("bind_address_port = 8000"))
        assertTrue(out.contains("chain_id = 143"), "network-wide settings stay")
        assertTrue(out.contains("network_name = \"mainnet\""))
        val template = resource("mainnet-full-node.toml")
        assertEquals(template.split("[[bootstrap.peers]]").size, out.split("[[bootstrap.peers]]").size)
        assertEquals(template.split("[[statesync.init_peers]]").size, out.split("[[statesync.init_peers]]").size)
        assertTrue(out.contains("enable_client = true") && out.contains("expand_to_group = true"), "full node switches stay on")
    }

    @Test
    fun testnet_splits_the_tcp_port_and_adds_the_direct_udp_port()
    {
        val out = patched(
            "testnet",
            mapOf(
                "self_address" to "\"203.0.113.9\"",
                "self_record_seq_num" to "2",
                "self_name_record_sig" to "\"ef01\"",
            ),
        )
        assertEquals("203.0.113.9", MonadNodeToml.peerValue(out, "self_address"))
        assertEquals("42022", MonadNodeToml.peerValue(out, "self_tcp_port"))
        assertEquals("42023", MonadNodeToml.peerValue(out, "self_auth_port"))
        assertEquals("42024", MonadNodeToml.peerValue(out, "self_direct_udp_auth_port"))
        assertEquals("2", MonadNodeToml.peerValue(out, "self_record_seq_num"))
        assertTrue(out.contains("bind_address_tcp_port = 42022"))
        assertTrue(out.contains("direct_udp_bind_address_port = 42024"))
        assertTrue(out.contains("authenticated_bind_address_port = 42023"))
        assertTrue(out.contains("chain_id = 10143"))
    }

    @Test
    fun a_key_the_template_lacks_is_appended_inside_its_section()
    {
        val template = "node_name = \"x\"\n[peer_discovery]\nself_address = \"<IP>\"\nrefresh_period = 20\n\n[network]\nbind_address_port = 8000\n"
        val out = MonadNodeToml.patch(template, "n", mapOf("peer_discovery" to mapOf("self_record_seq_num" to "1")))
        val peerBlock = out.substringAfter("[peer_discovery]").substringBefore("[network]")
        assertTrue(peerBlock.contains("self_record_seq_num = 1"), out)
        assertEquals("1", MonadNodeToml.peerValue(out, "self_record_seq_num"))
    }

    @Test
    fun a_missing_section_or_node_name_is_an_error_not_a_silent_skip()
    {
        assertFailsWith<IllegalArgumentException> {
            MonadNodeToml.patch("node_name = \"x\"\n", "n", mapOf("peer_discovery" to mapOf("a" to "1")))
        }
        assertFailsWith<IllegalArgumentException> {
            MonadNodeToml.patch("[network]\nbind_address_port = 1\n", "n", mapOf("network" to mapOf("bind_address_port" to "2")))
        }
    }

    @Test
    fun the_name_record_printout_is_read_as_toml_values()
    {
        val printed = """
            some banner
            self_address = 203.0.113.9:8000
            self_record_seq_num = 1
            self_name_record_sig = "abcd"
        """.trimIndent()
        val parsed = MonadNodeToml.parseNameRecord(printed)
        assertEquals("\"203.0.113.9:8000\"", parsed["self_address"])
        assertEquals("1", parsed["self_record_seq_num"])
        assertEquals("\"abcd\"", parsed["self_name_record_sig"])
        assertNull(parsed["banner"])
    }
}

class MonadCruftTest
{
    @Test
    fun the_script_cleans_only_under_the_state_dir_and_only_while_the_node_writes()
    {
        val script = MonadCruft.script(java.nio.file.Path.of("/data/monad/state"))
        assertTrue(script.startsWith("#!/bin/bash\n"))
        assertTrue(script.contains("STATE='/data/monad/state'"))
        assertTrue(script.contains("RETENTION_LEDGER=\${RETENTION_LEDGER:-600}"))
        assertTrue(script.contains("-mmin -20"), "no deletes while the ledger is not growing")
        assertTrue(script.contains("\"\$STATE/ledger/headers\"") && script.contains("\"\$STATE/ledger/bodies\""))
        assertFalse(script.contains("/home/monad"))
        assertEquals(4, Regex("-delete").findAll(script).count())
    }

    @Test
    fun a_quote_in_the_path_cannot_break_out_of_the_script()
    {
        val script = MonadCruft.script(java.nio.file.Path.of("/data/it's"))
        assertTrue(script.contains("STATE='/data/it'\\''s'"))
    }
}
