package rpcnode.toolkit.nodes.application.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotSentinel

class DiskRoleDirParamsTest
{
    private val layout = """{"roles":[{"id":"bor","dir":"/d/polygon/amoy/bor"},{"id":"heimdall","dir":" /d/polygon/amoy/heimdall "},{"id":"empty","dir":""}]}"""

    @Test
    fun every_role_with_a_directory_becomes_a_dir_parameter()
    {
        assertEquals(
            mapOf("dir_bor" to "/d/polygon/amoy/bor", "dir_heimdall" to "/d/polygon/amoy/heimdall"),
            diskRoleDirParams(layout),
        )
        assertEquals(emptyMap(), diskRoleDirParams(""))
        assertEquals(emptyMap(), diskRoleDirParams("not json"))
    }

    @Test
    fun the_parameters_ride_in_the_sentinel_url_and_only_there()
    {
        val url = EvmSnapshotSentinel.withParams("evm-snapshot://polygon/amoy/pruned", diskRoleDirParams(layout))
        val ref = EvmSnapshotSentinel.parse(url)!!
        assertEquals("/d/polygon/amoy/heimdall", ref.param("dir_heimdall"))
        assertEquals("/d/polygon/amoy/bor", ref.param("dir_bor"))
        assertEquals("https://x/y.tgz", EvmSnapshotSentinel.withParams("https://x/y.tgz", diskRoleDirParams(layout)), "a plain download URL is untouched")
    }
}
