package rpcnode.toolkit.chains.ton.infrastructure

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TonBootstrapScriptTest
{
    private val script = TonUnitBodies.bootstrapScript(
        env = "testnet",
        data = "/mnt/ton",
        markerDir = "/mnt/ton/.toolkit",
        logDir = "/mnt/ton/logs",
        thaPort = 8081,
        p2pPort = 30303,
        history = "dump",
    )

    @Test
    fun renders_without_leftover_placeholders()
    {
        assertFalse(script.contains("{{"), "unresolved placeholder in bootstrap script")
    }

    @Test
    fun carries_the_shared_clang_functions()
    {
        assertTrue(script.contains("ensure_clang_major() {"))
        assertTrue(script.contains("prepare_clang_for_unknown_distro() {"))
    }
}
