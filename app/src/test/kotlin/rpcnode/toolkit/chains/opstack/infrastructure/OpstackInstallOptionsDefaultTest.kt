package rpcnode.toolkit.chains.opstack.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import rpcnode.toolkit.catalog.domain.NetworkId

class OpstackInstallOptionsDefaultTest
{
    @Test
    fun unichain_starts_without_a_snapshot_unless_the_operator_chose_one()
    {
        assertEquals("none", OpstackInstallOptions.fromJson(null, NetworkId.UNICHAIN).restore)
        assertEquals("none", OpstackInstallOptions.fromJson("""{"node":"full"}""", NetworkId.UNICHAIN).restore)
        assertEquals("snapshot", OpstackInstallOptions.fromJson("""{"restore":"snapshot"}""", NetworkId.UNICHAIN).restore)
    }

    @Test
    fun every_other_network_restores_by_default()
    {
        assertEquals("snapshot", OpstackInstallOptions.fromJson(null, NetworkId.INK).restore)
        assertEquals("snapshot", OpstackInstallOptions.fromJson("""{"restore":"bogus"}""", NetworkId.UNICHAIN).restore)
        assertEquals("snapshot", OpstackInstallOptions.fromJson(null).restore)
    }
}
