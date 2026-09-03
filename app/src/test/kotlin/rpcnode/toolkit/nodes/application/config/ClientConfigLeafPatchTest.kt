package rpcnode.toolkit.nodes.application.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientConfigLeafPatchTest
{
    @Test
    fun hocon_replaces_listen_port_and_max_connections()
    {
        val template = """
            node {
              listen.port = 18888
              maxConnections = 30
              http {
                fullNodePort = 8090
              }
            }
        """.trimIndent()
        val out = ClientConfigLeafPatch.applyHocon(
            template,
            mapOf(
                "node.listen.port" to "18889",
                "node.maxConnections" to "40",
                "node.http.fullNodePort" to "18091",
            ),
        )
        assertTrue(out.contains("listen.port = 18889"))
        assertTrue(out.contains("maxConnections = 40"))
        assertTrue(out.contains("fullNodePort = 18091"))
    }

    @Test
    fun hocon_quotes_paths()
    {
        val template = """db.directory = "database""""
        val out = ClientConfigLeafPatch.applyHocon(
            template,
            mapOf("storage.db.directory" to "/data/rpcnode/tron/nile/fullnode/database"),
        )
        assertEquals(
            """db.directory = "/data/rpcnode/tron/nile/fullnode/database"""",
            out.trim(),
        )
    }

    @Test
    fun leaf_key_candidates_longest_first()
    {
        assertEquals(
            listOf("node.listen.port", "listen.port", "port"),
            ClientConfigLeafPatch.leafKeyCandidates("node.listen.port"),
        )
    }

    @Test
    fun hocon_appends_missing_pbft_ports()
    {
        val template = """
            node {
              http {
                fullNodePort = 8090
                solidityPort = 8091
              }
              rpc {
                port = 50051
              }
            }
        """.trimIndent()
        val out = ClientConfigLeafPatch.applyHocon(
            template,
            mapOf(
                "node.http.PBFTPort" to "18191",
                "node.rpc.PBFTPort" to "50071",
            ),
        )
        assertTrue(out.contains("node.http.PBFTPort = 18191"))
        assertTrue(out.contains("node.rpc.PBFTPort = 50071"))
        assertTrue(out.contains("# toolkit override"))
    }

    @Test
    fun ini_patches_network_keys_inside_env_section()
    {
        val template = """
            datadir=/old/global
            port=8333
            rpcport=8332

            [main]

            [testnet4]
        """.trimIndent()
        val out = ClientConfigLeafPatch.applyIni(
            template,
            mapOf(
                "datadir" to "/data/btc/blockchain",
                "port" to "18333",
                "rpcport" to "18332",
                "rpcbind" to "127.0.0.1",
            ),
            section = "testnet4",
        )
        assertTrue(out.contains("datadir=/data/btc/blockchain"))
        assertTrue(out.contains("# toolkit: use [testnet4] for this network"))
        assertTrue(out.contains("# port=8333"))
        assertTrue(out.contains("[testnet4]"))
        assertTrue(out.substringAfter("[testnet4]").contains("port=18333"))
        assertTrue(out.substringAfter("[testnet4]").contains("rpcport=18332"))
        assertTrue(out.substringAfter("[testnet4]").contains("rpcbind=127.0.0.1"))
        assertFalse(out.substringBefore("[main]").contains("\nport=18333"))
    }

    @Test
    fun ini_creates_missing_section_and_comments_global_ports()
    {
        val template = """
            port=8333
            rpcport=8332
            rpcbind=127.0.0.1
        """.trimIndent()
        val out = ClientConfigLeafPatch.applyIni(
            template,
            mapOf(
                "port" to "18333",
                "rpcport" to "18332",
                "rpcbind" to "127.0.0.1",
            ),
            section = "testnet4",
        )
        assertTrue(out.contains("# port=8333"))
        assertTrue(out.contains("[testnet4]"))
        assertTrue(out.substringAfter("[testnet4]").contains("port=18333"))
        assertFalse(Regex("(?m)^port=18333$").containsMatchIn(out.substringBefore("[testnet4]")))
    }

    @Test
    fun ini_mainnet_uses_main_section_not_global_ports()
    {
        val template = """
            port=8333
            rpcport=8332

            [main]
        """.trimIndent()
        val out = ClientConfigLeafPatch.applyIni(
            template,
            mapOf(
                "port" to "8333",
                "rpcport" to "8332",
                "server" to "1",
            ),
            section = "main",
        )
        assertTrue(out.contains("# port=8333"))
        assertTrue(out.substringAfter("[main]").contains("port=8333"))
        assertTrue(out.substringAfter("[main]").contains("server=1"))
    }

    @Test
    fun ini_ltc_testnet_uses_test_section()
    {
        val template = """
            port=9333
            rpcport=9332

            [main]

            [test]
        """.trimIndent()
        val out = ClientConfigLeafPatch.applyIni(
            template,
            mapOf(
                "port" to "19333",
                "rpcport" to "19332",
            ),
            section = "test",
        )
        assertTrue(out.contains("# port=9333"))
        assertTrue(out.substringAfter("[test]").contains("port=19333"))
        assertTrue(out.substringAfter("[test]").contains("rpcport=19332"))
    }

    @Test
    fun ini_omits_optional_blocksdir_from_template()
    {
        val template = """
            datadir=/old
            blocksdir=/media/storage12tb/rpcnode/bitcoin/mainnet/index
            port=8333

            [main]
        """.trimIndent()
        val out = ClientConfigLeafPatch.applyIni(
            template,
            mapOf("datadir" to "/data/btc/blockchain"),
            section = "main",
            omitKeys = setOf("blocksdir"),
        )
        assertTrue(out.contains("# toolkit: optional binding not used"))
        assertTrue(out.contains("# blocksdir=/media/storage12tb/rpcnode/bitcoin/mainnet/index"))
        assertFalse(Regex("(?m)^blocksdir=").containsMatchIn(out))
    }

    @Test
    fun ini_zmq_endpoints_land_in_env_section()
    {
        val template = """
            [main]
        """.trimIndent()
        val out = ClientConfigLeafPatch.applyIni(
            template,
            mapOf(
                "zmqpubrawblock" to "tcp://127.0.0.1:28332",
                "zmqpubrawtx" to "tcp://127.0.0.1:28333",
            ),
            section = "main",
        )
        assertTrue(out.substringAfter("[main]").contains("zmqpubrawblock=tcp://127.0.0.1:28332"))
        assertTrue(out.substringAfter("[main]").contains("zmqpubrawtx=tcp://127.0.0.1:28333"))
    }

    @Test
    fun client_config_ini_section_from_network_yaml()
    {
        val config = rpcnode.toolkit.networks.domain.model.ClientConfigFacts(
            format = "ini",
            envSections = mapOf(
                "mainnet" to "main",
                "testnet4" to "testnet4",
                "testnet" to "test",
            ),
        )
        assertEquals("testnet4", clientConfigIniSection(config, "testnet4"))
        assertEquals("test", clientConfigIniSection(config, "testnet"))
        assertEquals("main", clientConfigIniSection(config, "mainnet"))
        assertEquals(null, clientConfigIniSection(config.copy(format = "hoocon"), "mainnet"))
    }
}
