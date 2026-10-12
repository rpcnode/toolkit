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

    @Test
    fun opens_the_http_api_to_all_interfaces_unless_told_otherwise()
    {
        assertTrue(script.contains("""s/--host [0-9a-fA-F.:]+/--host ${'$'}{RPCNODE_TON_API_BIND:-0.0.0.0}/g"""))
        assertTrue(script.contains("s/--port [0-9]+/--port "))
    }

    @Test
    fun carries_the_env_zero_state_and_config_url()
    {
        assertTrue(script.contains("ZERO_STATE='823F81F306FF02694F935CF5021548E3CE2B86B529812AF6A12148879E95A128'"))
        assertTrue(script.contains("GLOBAL_CONFIG_URL='https://ton.org/testnet-global.config.json'"))
        assertTrue(script.contains("ensure_global_config() {"))
    }
}

class TonNodeStartScriptTest
{
    private val script = TonUnitBodies.nodeStartScript(
        markerPath = "/mnt/ton/.toolkit/bootstrap.done",
        bootstrapUnit = "rpcnode-ton-testnet-bootstrap.service",
        logFile = "/mnt/ton/logs/ton.log",
    )

    @Test
    fun restarts_the_oneshot_bootstrap_unit_instead_of_a_no_op_start()
    {
        assertTrue(script.contains("systemctl restart --no-block \"\$BOOT_UNIT\""))
        assertFalse(script.contains("systemctl start --no-block \"\$BOOT_UNIT\""))
        assertTrue(script.contains("== activating"), "a running bootstrap must be left alone")
        assertFalse(script.contains("{{"))
    }
}
