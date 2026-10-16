package rpcnode.toolkit.nodes.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import rpcnode.toolkit.nodes.domain.model.NodeLifecycleStamps
import rpcnode.toolkit.nodes.domain.model.NodeStatus

class NodeLifecycleStampsTest
{
    private val sync = NodeStatus.SYNC
    private val active = NodeStatus.ACTIVE

    @Test
    fun the_install_date_starts_with_the_first_status_after_the_ports_step_and_never_moves()
    {
        assertEquals("", NodeLifecycleStamps.installStarted("", NodeStatus.AWAITING_PORTS, "t1"), "still waiting for ports")
        assertEquals("t2", NodeLifecycleStamps.installStarted("", NodeStatus.parse("host_deps_running"), "t2"))
        assertEquals("t2", NodeLifecycleStamps.installStarted("t2", sync, "t3"), "a later status keeps the first date")
        assertEquals("t2", NodeLifecycleStamps.installStarted("t2", NodeStatus.STOPPED, "t4"))
        assertEquals("t2", NodeLifecycleStamps.installStarted("t2", NodeStatus.AWAITING_PORTS, "t5"), "going back to ports does not erase it")
    }

    @Test
    fun the_synced_date_is_the_first_time_the_node_is_active_and_stays()
    {
        assertEquals("", NodeLifecycleStamps.synced("", sync, "t1"))
        assertEquals("", NodeLifecycleStamps.synced("", NodeStatus.STOPPED, "t1"))
        assertEquals("t2", NodeLifecycleStamps.synced("", active, "t2"))
        assertEquals("t2", NodeLifecycleStamps.synced("t2", sync, "t3"), "falling back to sync keeps it")
        assertEquals("t2", NodeLifecycleStamps.synced("t2", active, "t4"))
    }

    @Test
    fun a_blank_current_value_counts_as_not_set()
    {
        assertEquals("now", NodeLifecycleStamps.installStarted("  ", sync, "now"))
        assertEquals("now", NodeLifecycleStamps.synced("  ", active, "now"))
    }
}
