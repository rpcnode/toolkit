package rpcnode.toolkit.chains.polygon.infrastructure.proc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PolygonConfigPatchMonikerTest
{
    private fun amoyTemplate(): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("polygon/heimdall-amoy-v0.12.2-config.toml")).readBytes().toString(Charsets.UTF_8)

    private fun moniker(text: String): String? =
        Regex("""(?m)^moniker\s*=\s*"([^"]*)"""").find(text)?.groupValues?.get(1)

    @Test
    fun the_amoy_package_ships_an_empty_moniker_that_heimdalld_rejects_and_it_gets_a_name()
    {
        val template = amoyTemplate()
        assertEquals("", moniker(template), "the real v0.12.2 amoy config.toml has moniker = \"\"")
        val patched = PolygonConfigPatch.ensureMoniker(template, "rpcnode-ubuntu")
        assertEquals("rpcnode-ubuntu", moniker(patched))
        assertEquals(template.lines().size, patched.lines().size, "only that one line changed")
        assertTrue(patched.contains("proxy_app = \"tcp://127.0.0.1:26658\""), "everything else is untouched")
    }

    @Test
    fun a_usable_moniker_is_kept_so_an_operator_choice_survives_every_start()
    {
        val text = "# name\nmoniker = \"my-heimdall\"\n[p2p]\nladdr = \"x\"\n"
        assertEquals(text, PolygonConfigPatch.ensureMoniker(text, "rpcnode-host"))
        assertEquals(text, PolygonConfigPatch.ensureMoniker(PolygonConfigPatch.ensureMoniker(text, "a"), "b"), "idempotent")
    }

    @Test
    fun blank_non_ascii_or_tab_monikers_are_replaced()
    {
        for (bad in listOf("", "   ", "узел", "a\tb"))
        {
            val out = PolygonConfigPatch.ensureMoniker("moniker = \"$bad\"\n", "rpcnode-host")
            assertEquals("rpcnode-host", moniker(out), "moniker '$bad'")
        }
    }

    @Test
    fun a_trailing_comment_stays_and_a_missing_line_is_added_as_a_root_key_before_the_first_table()
    {
        val out = PolygonConfigPatch.ensureMoniker("moniker = \"\" # node name\n[p2p]\nx = 1\n", "h")
        assertTrue(out.startsWith("moniker = \"h\" # node name\n"), out)
        val added = PolygonConfigPatch.ensureMoniker("proxy_app = \"a\"\n\n[p2p]\nx = 1\n", "h")
        assertTrue(added.indexOf("moniker = \"h\"") in 0 until added.indexOf("[p2p]"), added)
        assertEquals("h", moniker(added))
    }

    @Test
    fun the_fallback_is_sanitised_to_a_safe_ascii_name()
    {
        assertEquals("rpcnode-my-host_1.example", moniker(PolygonConfigPatch.ensureMoniker("moniker = \"\"\n", "rpcnode-my-host_1.example")))
        assertEquals("rpcnode-heimdall", moniker(PolygonConfigPatch.ensureMoniker("moniker = \"\"\n", "ян")), "nothing safe left: a fixed name")
        assertEquals("rm", moniker(PolygonConfigPatch.ensureMoniker("moniker = \"\"\n", "ян \"; rm\t")), "only [A-Za-z0-9._-] survive")
        val tricky = moniker(PolygonConfigPatch.ensureMoniker("moniker = \"\"\n", "bad name\"; x"))
        assertFalse(tricky!!.contains('"') || tricky.contains(' ') || tricky.contains(';'), tricky)
        assertTrue(moniker(PolygonConfigPatch.ensureMoniker("moniker = \"\"\n", "x".repeat(200)))!!.length <= 40)
    }
}
