package rpcnode.toolkit.nodes.application.snapshot

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClientsNeedResyncTest
{
    private val full = "/mnt/r/rpcnode/tron/nile/fullnode"
    private val lite = "/mnt/r/rpcnode/tron/nile/litefullnode"

    @Test
    fun same_type_and_dir_needs_no_resync() =
        assertFalse(clientsNeedResync("full", "full", full, full))

    @Test
    fun lite_moves_the_dir() =
        assertTrue(clientsNeedResync("full", "lite", full, lite))

    @Test
    fun archive_keeps_the_dir_but_changes_config() =
        assertTrue(clientsNeedResync("full", "archive", full, full))

    @Test
    fun switching_between_any_types_resyncs() {
        assertTrue(clientsNeedResync("lite", "archive", lite, full))
        assertTrue(clientsNeedResync("archive", "lite", full, lite))
        assertTrue(clientsNeedResync("archive", "full", full, full))
    }

    @Test
    fun unknown_previous_type_and_dir_are_not_treated_as_a_change() =
        assertFalse(clientsNeedResync(null, "full", null, full))

    @Test
    fun type_comparison_ignores_case() =
        assertFalse(clientsNeedResync("FULL", "full", full, full))
}
