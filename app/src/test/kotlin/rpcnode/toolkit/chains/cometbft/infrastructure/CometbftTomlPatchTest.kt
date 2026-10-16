package rpcnode.toolkit.chains.cometbft.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CometbftTomlPatchTest
{
    private val sample = """
        # top comment
        moniker = "x"
        [rpc]
        laddr = "tcp://127.0.0.1:26657"
        # keep this
        unsafe = true

        [p2p]
        laddr = "tcp://0.0.0.0:26656"
        seeds = ""
    """.trimIndent()

    @Test
    fun replaces_existing_keys_and_keeps_comments()
    {
        val out = CometbftTomlPatch.set(sample, "rpc", "laddr", "\"tcp://0.0.0.0:41300\"")
        assertTrue(out.contains("laddr = \"tcp://0.0.0.0:41300\""))
        assertTrue(out.contains("# keep this"))
        assertTrue(out.contains("laddr = \"tcp://0.0.0.0:26656\""), "p2p.laddr untouched")
    }

    @Test
    fun appends_missing_key_inside_its_section()
    {
        val out = CometbftTomlPatch.set(sample, "rpc", "max_body_bytes", "1000000")
        val lines = out.lines()
        val at = lines.indexOf("max_body_bytes = 1000000")
        assertTrue(at > lines.indexOf("[rpc]") && at < lines.indexOf("[p2p]"), out)
    }

    @Test
    fun creates_a_missing_section_and_top_level_keys_go_before_tables()
    {
        val s = CometbftTomlPatch.set(sample, "instrumentation", "prometheus", "true")
        assertTrue(s.contains("[instrumentation]\nprometheus = true"))
        val t = CometbftTomlPatch.set(sample, "", "pruning", "\"default\"")
        assertTrue(t.lines().indexOf("pruning = \"default\"") < t.lines().indexOf("[rpc]"))
        assertEquals(1, t.lines().count { it.startsWith("pruning") })
    }

    @Test
    fun is_idempotent()
    {
        val once = CometbftTomlPatch.apply(sample, listOf(TomlSetting("c", "rpc", "unsafe", "false", TomlSetting.Kind.RAW), TomlSetting("c", "p2p", "seeds", "a@b:1")))
        assertEquals(once, CometbftTomlPatch.apply(once, listOf(TomlSetting("c", "rpc", "unsafe", "false", TomlSetting.Kind.RAW), TomlSetting("c", "p2p", "seeds", "a@b:1"))))
        assertTrue(once.contains("unsafe = false"))
    }
}
