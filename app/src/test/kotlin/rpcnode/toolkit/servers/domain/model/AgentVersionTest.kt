package rpcnode.toolkit.servers.domain.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentVersionTest
{
    @Test
    fun empty_or_equal_is_not_outdated()
    {
        assertFalse(agentVersionOutdated("", "0.2.0"))
        assertFalse(agentVersionOutdated("0.1.1", ""))
        assertFalse(agentVersionOutdated("0.1.1", "0.1.1"))
        assertFalse(agentVersionOutdated("v0.1.1", "0.1.1"))
    }

    @Test
    fun older_local_is_outdated()
    {
        assertTrue(agentVersionOutdated("0.1.0", "0.1.1"))
        assertTrue(agentVersionOutdated("0.1.1", "0.2.0"))
        assertFalse(agentVersionOutdated("0.2.0", "0.1.9"))
    }
}
