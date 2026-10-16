package rpcnode.toolkit.nodes.infrastructure.host

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeProxyEnvTest
{
    @Test
    fun the_file_carries_upper_and_lower_case_variables_and_blank_removes_it()
    {
        val dir = Files.createTempDirectory("proxy-env")
        try
        {
            NodeProxyEnv.write(dir, "http://bob:p\"w@proxy.example:3128")
            val text = Files.readString(dir.resolve(".toolkit/proxy.env"))
            assertTrue("https_proxy=" in text && "HTTPS_PROXY=" in text && "ALL_PROXY=" in text)
            assertTrue("NO_PROXY=\"localhost,127.0.0.1,::1\"" in text)
            assertEquals("http://bob:p\"w@proxy.example:3128", NodeProxyEnv.read(dir))

            NodeProxyEnv.write(dir, "  ")
            assertFalse(Files.exists(dir.resolve(".toolkit/proxy.env")))
            assertNull(NodeProxyEnv.read(dir))
            assertEquals(emptyMap(), NodeProxyEnv.vars(""))
        }
        finally
        {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun every_unit_references_the_file_once_right_after_service()
    {
        val body = "[Unit]\nDescription=x\n\n[Service]\nExecStart=/bin/true\n"
        val once = NodeProxyEnv.withEnvironmentFile(body, Path.of("/data/arc"))
        assertEquals(
            "[Unit]\nDescription=x\n\n[Service]\nEnvironmentFile=-/data/arc/.toolkit/proxy.env\nExecStart=/bin/true\n",
            once,
        )
        assertEquals(once, NodeProxyEnv.withEnvironmentFile(once, Path.of("/data/arc")))
        assertEquals("http://bob:***@h:1", NodeProxyEnv.mask("http://bob:pw@h:1"))
    }
}
