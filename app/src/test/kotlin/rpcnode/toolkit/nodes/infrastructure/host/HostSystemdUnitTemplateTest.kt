package rpcnode.toolkit.nodes.infrastructure.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostSystemdUnitTemplateTest
{
    @Test
    fun loads_network_template_and_substitutes()
    {
        val rendered = HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("tron"),
            mapOf(
                "description" to "rpcnode tron/nile",
                "network" to "tron",
                "env" to "nile",
                "node_id" to "abc",
                "node_dir" to "/data/tron",
                "log_file" to "/data/tron/logs/node.out",
                "exec_start" to "/usr/bin/java -jar FullNode.jar",
            ),
        )
        assertTrue(rendered.contains("WorkingDirectory=/data/tron"))
        assertTrue(rendered.contains("ExecStart=/usr/bin/java -jar FullNode.jar"))
        assertTrue(rendered.contains("StandardOutput=append:/data/tron/logs/node.out"))
        assertTrue(!rendered.contains("{{"))
    }

    @Test
    fun unknown_network_falls_back_to_default()
    {
        val tmpl = HostSystemdUnitTemplate.load("not-a-real-chain")
        assertTrue(tmpl.contains("{{exec_start}}"))
        assertEquals(
            HostSystemdUnitTemplate.load("default").trim(),
            tmpl.trim(),
        )
    }
}
