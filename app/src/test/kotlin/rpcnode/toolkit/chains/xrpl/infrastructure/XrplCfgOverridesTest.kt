package rpcnode.toolkit.chains.xrpl.infrastructure

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrplCfgOverridesTest
{
    private val cfg = XrplUnitBodies.cfg(
        cluster = XrplClusters.lookup("mainnet"),
        etc = Path.of("/n/etc"),
        data = Path.of("/n/data"),
        ports = XrplPortTable.forEnv("mainnet"),
        policy = XrplHistory.DEFAULT,
        hasLedger = false,
    )

    private fun section(text: String, key: String): List<String> =
        Regex("(?m)^\\[$key]\\s*\\n([^\\n\\[]*)").findAll(text).map { it.groupValues[1].trim() }.toList()

    @Test
    fun existing_single_line_sections_are_rewritten_not_duplicated()
    {
        val out = XrplUnitBodies.applyCfgOverrides(cfg, "peers_max=50\nnode_size=huge")
        assertEquals(listOf("50"), section(out, "peers_max"))
        assertEquals(listOf("huge"), section(out, "node_size"))
    }

    @Test
    fun a_missing_section_is_appended_once()
    {
        assertTrue("[crawl]" !in cfg || section(cfg, "crawl").size == 1)
        val out = XrplUnitBodies.applyCfgOverrides(cfg, "crawl=0")
        assertEquals(listOf("0"), section(out, "crawl"))
    }

    @Test
    fun unknown_keys_and_bad_values_are_ignored()
    {
        val out = XrplUnitBodies.applyCfgOverrides(cfg, "peers_max=99999\nnode_size=gigantic\nsigning_support=true\nrpc_startup=x")
        assertEquals(cfg, out)
        assertFalse("signing_support" in out)
        assertEquals(cfg, XrplUnitBodies.applyCfgOverrides(cfg, null))
    }
}
