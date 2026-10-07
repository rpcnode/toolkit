package rpcnode.toolkit.agent.infrastructure.proc

import kotlin.test.Test
import kotlin.test.assertTrue

class EnsureHostCurlTest
{
    @Test
    fun ensure_returns_curl_when_present()
    {
        if (!EnsureHostCurl.onPath("curl"))
        {
            return
        }
        assertTrue(EnsureHostCurl.ensure() == "curl")
    }
}
