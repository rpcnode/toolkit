package rpcnode.toolkit.chains.xrpl.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class XrplRunScriptTest
{
    private val script = XrplUnitBodies.runScript(
        nodeDir = "/data/xrpl/testnet",
        srcTar = "/data/xrpl/testnet/xrpld-src.tar.gz",
        bin = "/data/xrpl/testnet/bin/xrpld",
        conf = "/data/xrpl/testnet/xrpld.cfg",
        logFile = "/data/xrpl/testnet/logs/xrpld.log",
    )

    @Test
    fun every_placeholder_is_filled()
    {
        assertFalse(Regex("\\{\\{[a-z_]+}}").containsMatchIn(script), script)
        assertContains(script, "ROOT='/data/xrpl/testnet'")
        assertContains(script, "CLANG_MAJOR=22")
        assertContains(script, "ensure_clang_major()")
    }

    @Test
    fun builds_with_conan_cmake_and_runs_the_binary()
    {
        assertContains(script, "conan install .. --output-folder . --build missing --settings build_type=Release")
        assertContains(script, "-Dxrpld=ON -Dtests=OFF")
        assertContains(script, "--target xrpld")
        assertContains(script, "exec \"\$BIN\" --conf \"\$CONF\"")
    }

    @Test
    fun a_prebuilt_binary_is_preferred_and_the_local_compile_is_the_fallback()
    {
        val withPrebuilt = XrplUnitBodies.runScript(
            nodeDir = "/n", srcTar = "/n/xrpld-src.tar.gz", bin = "/n/bin/xrpld", conf = "/n/xrpld.cfg",
            logFile = "/n/logs/xrpld.log", prebuilt = "/n/xrpld-x86_64",
        )
        assertContains(withPrebuilt, "PREBUILT='/n/xrpld-x86_64'")
        assertContains(withPrebuilt, "use_prebuilt()")
        assertContains(withPrebuilt, "does not run on this host — compiling from source")
        assertFalse(Regex("\\{\\{[a-z_]+}}").containsMatchIn(withPrebuilt))
        assertContains(script, "PREBUILT=''")
        val file = Files.createTempFile("xrpl-run-pre", ".sh")
        Files.writeString(file, withPrebuilt)
        val p = ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), out)
    }

    @Test
    fun passes_bash_syntax_check()
    {
        val file = Files.createTempFile("xrpl-run", ".sh")
        Files.writeString(file, script)
        val p = ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(0, p.waitFor(), out)
    }

    @Test
    fun unit_starts_the_run_script_and_stops_the_binary()
    {
        val unit = XrplUnitBodies.unit(
            env = "testnet",
            nodeDir = "/n",
            execStart = "/n/bin/rpcnode-xrpl-run.sh",
            bin = "/n/bin/xrpld",
            conf = "/n/xrpld.cfg",
            logFile = "/n/logs/xrpld.log",
        )
        assertContains(unit, "ExecStart=/n/bin/rpcnode-xrpl-run.sh")
        assertContains(unit, "/n/bin/xrpld --conf /n/xrpld.cfg server_stop")
        assertTrue(!unit.contains("{{"))
    }
}
