package rpcnode.toolkit.chains.cardano.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.HostNodeDiagnostics

class CardanoDiagnosticsTest
{
    private fun req(id: String, port: Int, env: String = "preview", dir: String? = null) =
        DiagnosticRequest(id, port, params = buildMap { put("env", env); if (dir != null) put("node_dir", dir) })

    private val ogmiosOk = "ogmios_connected 1.0\nogmios_network_synchronization 1.0\nogmios_tip_block 100\nogmios_active_connections 2.0"

    @Test
    fun a_disabled_indexer_shows_nothing()
    {
        assertEquals("ok", CardanoDiagnostics.ogmios(req("cardano_ogmios", 41230), mode = { CardanoIndexerMode.NONE }).level)
        assertEquals("ok", CardanoDiagnostics.ogmios(req("cardano_ogmios", 41230), mode = { CardanoIndexerMode.DBSYNC }).level)
        assertEquals("ok", CardanoDiagnostics.dbSync(req("cardano_dbsync", 41237), mode = { CardanoIndexerMode.OGMIOS }).level)
    }

    @Test
    fun ogmios_levels()
    {
        val check = req("cardano_ogmios", 41230)
        val on = { CardanoIndexerMode.OGMIOS }
        assertEquals("ok", CardanoDiagnostics.ogmios(check, on) { ogmiosOk }.level)
        val down = CardanoDiagnostics.ogmios(check, on) { null }
        assertEquals("warn", down.level)
        assertTrue(down.hint.contains("rpcnode-cardano-preview-ogmios.service"))
        assertEquals("warn", CardanoDiagnostics.ogmios(check, on) { "ogmios_connected 0.0" }.level)
        val syncing = CardanoDiagnostics.ogmios(check, on) { "ogmios_connected 1.0\nogmios_network_synchronization 0.42\nogmios_tip_block 5" }
        assertEquals("info", syncing.level)
        assertTrue(syncing.title.contains("42.0%"), syncing.title)
    }

    @Test
    fun db_sync_levels()
    {
        val check = req("cardano_dbsync", 41237)
        val on = { CardanoIndexerMode.BOTH }
        val cluster = CardanoClusters.lookup("preview")
        val now = cluster.shelleyStartUnix + 1_000_000L
        fun metrics(slot: Long, block: Long = 10) =
            "cardano_db_sync_db_block_height $block.0\ncardano_db_sync_db_slot_height $slot.0\ncardano_db_sync_node_block_height 20.0"
        // waiting for PostgreSQL: no metrics and no ready marker
        val waiting = CardanoDiagnostics.dbSync(check, on, { null }, { false }, { now })
        assertEquals("info", waiting.level)
        assertTrue(waiting.hint.contains("rpcnode-cardano-preview-dbsync-prepare.service"))
        // prepared but not answering
        assertEquals("warn", CardanoDiagnostics.dbSync(check, on, { null }, { true }, { now }).level)
        // still migrating: gauges at 0
        assertEquals("info", CardanoDiagnostics.dbSync(check, on, { metrics(0, 0) }, { true }, { now }).level)
        // syncing: percentage shown
        val syncing = CardanoDiagnostics.dbSync(check, on, { metrics(250_000) }, { true }, { now })
        assertEquals("info", syncing.level)
        assertTrue(syncing.title.contains("25.0%"), syncing.title)
        assertTrue(syncing.detail.contains("block 10 of 20"), syncing.detail)
        // at tip: hidden
        assertEquals("ok", CardanoDiagnostics.dbSync(check, on, { metrics(999_950) }, { true }, { now }).level)
    }

    @Test
    fun the_mode_comes_from_the_state_file_the_starter_writes()
    {
        val dir = Files.createTempDirectory("cardano-diag")
        try
        {
            val check = req("cardano_ogmios", 41230, dir = dir.toString())
            assertEquals(CardanoIndexerMode.NONE, CardanoDiagnostics.readMode(check))
            Files.createDirectories(dir.resolve(".toolkit"))
            Files.writeString(dir.resolve(CardanoIndexerBodies.STATE_FILE), """{"mode":"both","env":"preview"}""")
            assertEquals(CardanoIndexerMode.BOTH, CardanoDiagnostics.readMode(check))
        }
        finally
        {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun the_host_dispatches_both_checks()
    {
        val out = HostNodeDiagnostics.run(listOf(req("cardano_ogmios", 41230), req("cardano_dbsync", 41237)))
        assertEquals(listOf("cardano_ogmios", "cardano_dbsync"), out.map { it.id })
        assertTrue(out.none { it.level == "unknown" && it.title.startsWith("Unknown check") })
    }
}
