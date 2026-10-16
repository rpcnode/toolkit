package rpcnode.toolkit.nodes.infrastructure.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnitFlagOverridesTest
{
    private val spaceUnit = """
        [Service]
        ExecStart=/opt/geth/geth \
          --datadir /data \
          --cache 2048 \
          --maxpeers 100 \
          --port 30303
        Restart=on-failure
    """.trimIndent()

    private val equalsUnit = """
        [Service]
        ExecStart=/opt/nitro \
          --parent-chain.connection.url=http://x \
          --http.port=8547 \
          --execution.caching.trie-clean-cache=600
        Restart=always
    """.trimIndent()

    @Test
    fun existing_flag_value_is_replaced_in_the_units_own_style()
    {
        val out = UnitFlagOverrides.apply(spaceUnit, "cache=8192")
        assertTrue("--cache 8192" in out && "--cache 2048" !in out, out)
        val eq = UnitFlagOverrides.apply(equalsUnit, "execution.caching.trie-clean-cache=1200")
        assertTrue("--execution.caching.trie-clean-cache=1200" in eq, eq)
    }

    @Test
    fun new_flags_are_appended_in_the_majority_style_and_the_unit_stays_one_command()
    {
        val out = UnitFlagOverrides.apply(equalsUnit, "execution.rpc.gas-cap=100000000\nhttp.api=eth,net")
        assertTrue("--execution.rpc.gas-cap=100000000" in out && "--http.api=eth,net" in out, out)
        val sp = UnitFlagOverrides.apply(spaceUnit, "cache.blocklogs 128")
        assertTrue("--cache.blocklogs 128" in sp, sp)
        val exec = out.lines().dropWhile { !it.startsWith("ExecStart=") }.takeWhile { !it.startsWith("Restart=") }
        assertTrue(exec.dropLast(1).all { it.trimEnd().endsWith("\\") }, exec.joinToString("\n"))
        assertTrue(out.lines().last() == "Restart=always" || "Restart=always" in out)
    }

    @Test
    fun locked_unsafe_and_malformed_lines_are_rejected()
    {
        val p = UnitFlagOverrides.parse("datadir=/x\nhttp.port=1\nrpc-bind-address=0.0.0.0\ncache=1;rm\n1bad=1\nrpc.max-connections=5000")
        assertEquals(mapOf("rpc.max-connections" to "5000"), p.overrides)
        assertEquals(5, p.rejected.size)
        assertEquals(spaceUnit, UnitFlagOverrides.apply(spaceUnit, "datadir=/evil"))
    }

    @Test
    fun pascal_case_keys_are_accepted_and_the_locked_ones_rejected()
    {
        val p = UnitFlagOverrides.parse(
            "JsonRpc.MaxBatchSize=500\nNetwork.MaxActivePeers=50\nJsonRpc.EnabledModules=Eth,Net,Web3\n" +
                "Network.P2PPort=1\nNetwork.DiscoveryPort=2\nJsonRpc.JwtSecretFile=/x\nJsonRpc.Host=0.0.0.0\nMetrics.ExposePort=9",
        )
        assertEquals(
            mapOf("JsonRpc.MaxBatchSize" to "500", "Network.MaxActivePeers" to "50", "JsonRpc.EnabledModules" to "Eth,Net,Web3"),
            p.overrides,
        )
        assertEquals(5, p.rejected.size, p.rejected.toString())
        val unit = "[Service]\nExecStart=/n \\\n  -c gnosis \\\n  --JsonRpc.MaxBatchSize 1000 \\\n  --JsonRpc.EnabledModules Eth,Net,Web3,Subscribe,Rpc\n"
        val out = UnitFlagOverrides.apply(unit, "JsonRpc.MaxBatchSize=500\nJsonRpc.EnabledModules=Eth,Net,Web3")
        assertTrue("--JsonRpc.MaxBatchSize 500" in out && "--JsonRpc.EnabledModules Eth,Net,Web3\n" in out + "\n", out)
    }

    @Test
    fun repeated_flags_are_lists_and_stay_untouched()
    {
        val unit = "[Service]\nExecStart=/b --feed a --feed b --x 1\n"
        val out = UnitFlagOverrides.apply(unit, "feed=c\nx=2")
        assertEquals(2, Regex("--feed").findAll(out).count(), out)
        assertTrue("--feed a" in out && "--feed b" in out && "--feed c" !in out, out)
        assertTrue("--x 2" in out, out)
        assertEquals(unit, UnitFlagOverrides.apply(unit, ""))
        assertEquals(unit, UnitFlagOverrides.apply(unit, null))
    }

    @Test
    fun args_round_trip_and_garbage_is_ignored()
    {
        val args = listOf("--toolkit-env=x", UnitFlagOverrides.ARG_PREFIX + UnitFlagOverrides.encode("cache=1"))
        assertEquals("cache=1", UnitFlagOverrides.fromArgs(args))
        assertEquals("", UnitFlagOverrides.fromArgs(listOf(UnitFlagOverrides.ARG_PREFIX + "%%%")))
    }
}
