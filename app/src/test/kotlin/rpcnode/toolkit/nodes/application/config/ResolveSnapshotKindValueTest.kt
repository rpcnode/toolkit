package rpcnode.toolkit.nodes.application.config

import kotlin.test.Test
import kotlin.test.assertEquals
import rpcnode.toolkit.networks.domain.model.ClientConfigBindingFacts

class ResolveSnapshotKindValueTest
{
    private val binding = ClientConfigBindingFacts(
        path = "storage.transHistory.switch",
        source = "snapshot_kind",
        map = mapOf("full" to "on", "lite" to "off", "archive" to "on"),
        default = "on",
    )

    @Test
    fun lite_is_off()
    {
        assertEquals("off", resolveSnapshotKindValue(binding, "lite", "lite"))
    }

    @Test
    fun archive_is_on()
    {
        assertEquals("on", resolveSnapshotKindValue(binding, "archive", "archive"))
    }

    @Test
    fun full_is_on()
    {
        assertEquals("on", resolveSnapshotKindValue(binding, "full", "full"))
    }

    @Test
    fun missing_uses_default()
    {
        assertEquals("on", resolveSnapshotKindValue(binding, "", null))
    }
}
