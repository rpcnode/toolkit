package rpcnode.toolkit.clients.application

import kotlin.test.Test
import kotlin.test.assertEquals

class InferLaunchTest
{
    @Test
    fun juno_zip_launches_bin_juno()
    {
        val launch = inferLaunch("juno", listOf(InstallPlanFile(role = "artifact", path = "juno-x86_64-linux.zip", arch = "x86_64")))
        assertEquals(InstallPlanLaunch(kind = "binary", entry = "bin/juno"), launch)
    }
}
