package rpcnode.toolkit.chains.l2

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.nodes.infrastructure.host.UnitFlagOverrides

/** The shell side of the flag overrides: `apply_extra_flags` in the optimism / arc / opstack run scripts. */
class RunScriptFlagOverridesTest
{
    private fun bashOk(): Boolean = runCatching {
        val p = ProcessBuilder("bash", "-c", "(( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 3) ))").start()
        p.waitFor() == 0
    }.getOrDefault(false)

    private fun function(template: String): String
    {
        val text = Path.of("src/main/resources/chains/$template/run.sh.tmpl").let(Files::readString)
        val start = text.indexOf("EXTRA_FLAGS_FILE=")
        val end = text.indexOf("\n}\n", text.indexOf("apply_extra_flags()")) + 3
        return text.substring(start, end)
    }

    private fun run(template: String, overrides: String, args: List<String>): List<String>
    {
        val dir = Files.createTempDirectory("rpcnode-xf")
        Files.createDirectories(dir.resolve("logs"))
        UnitFlagOverrides.writeFile(dir, overrides)
        val script = """
            ROOT='$dir'
            LOG='$dir/logs/node.out'
            ${function(template)}
            a=(${args.joinToString(" ") { "'$it'" }})
            apply_extra_flags a
            printf '%s\n' "${'$'}{a[@]}"
        """.trimIndent()
        val p = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        return out.lines().filter { it.isNotEmpty() }
    }

    @Test
    fun existing_flags_are_replaced_and_new_ones_appended()
    {
        if (!bashOk()) return
        val base = listOf("node", "--http.api=eth,debug", "--rpc.max-connections", "250", "--full")
        for (t in listOf("optimism", "arc", "opstack"))
        {
            val out = run(t, "http.api=eth,net\nrpc.max-connections=3000\nrpc-cache.max-receipts=20000\ndatadir=/evil", base)
            assertTrue("--http.api=eth,net" in out && "--http.api=eth,debug" !in out, "$t $out")
            assertTrue("--rpc.max-connections=3000" in out && "250" !in out, "$t $out")
            assertTrue("--rpc-cache.max-receipts=20000" in out, "$t $out")
            assertTrue(out.none { "evil" in it }, "$t $out")
            assertEquals("node", out.first(), "$t $out")
            assertTrue("--full" in out, "$t $out")
        }
    }

    @Test
    fun without_overrides_the_args_are_untouched()
    {
        if (!bashOk()) return
        val base = listOf("node", "--a=1", "--b", "2")
        for (t in listOf("optimism", "arc", "opstack")) assertEquals(base, run(t, "", base))
    }
}
