package rpcnode.toolkit.chains.opstack.infrastructure.proc

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackNetworks

class OpstackNodeProcessStarterTest
{
    private val starter = OpstackNodeProcessStarter(OpstackNetworks.INK)

    private fun args(vararg extra: String) = listOf(
        "--toolkit-env=mainnet", "--toolkit-mode=full", "--toolkit-restore=snapshot",
        "--toolkit-l1-rpc=https://l1.example/key123", "--toolkit-l1-beacon=https://beacon.example",
    ) + extra

    @Test
    fun the_run_script_is_written_owner_only_because_it_holds_the_l1_key_and_the_snapshot_url()
    {
        val dir = Files.createTempDirectory("opstack-start")
        val prepared = starter.prepare("mainnet", dir.toString(), args("--toolkit-snapshot-url=https://files.example/ink.tar.gz?sig=abc"))
        val ok = assertIs<OpstackNodeProcessStarter.Prepared.Ok>(prepared)
        assertEquals(dir.resolve("bin/rpcnode-ink-run.sh"), ok.script)
        val perms = Files.getPosixFilePermissions(ok.script)
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE), perms)
        val body = Files.readString(ok.script)
        assertTrue(body.contains("L1_RPC='https://l1.example/key123'"))
        assertTrue(body.contains("SNAP_CUSTOM='https://files.example/ink.tar.gz?sig=abc'"))
        assertTrue(ok.unitBody.contains("ExecStart=${ok.script}"))
        assertTrue(Files.isDirectory(dir.resolve("execution")) && Files.isDirectory(dir.resolve("consensus")) && Files.isDirectory(dir.resolve("snapshots")))
        assertTrue(Files.isDirectory(dir.resolve("logs")) && Files.isDirectory(dir.resolve(".toolkit")))
    }

    @Test
    fun a_snapshot_url_that_cannot_be_extracted_or_could_inject_shell_is_refused_before_anything_is_written()
    {
        val dir = Files.createTempDirectory("opstack-start-bad")
        for (bad in listOf(
            "ftp://x/y.tar.gz", "https://x/y.zip", "https://x/y.tar.gz'; rm -rf /", "https://x/a b.tar.gz", "file:///etc/passwd.tar",
            "https://x/y.tar.gz\$(id)", "https://x/\$(id).tar.gz", "https://x/y`id`.tar.gz", "https://x/y.tar.gz\"", "https://x/a;b.tar.gz", "https://x/a|b.tar.gz",
        ))
        {
            val prepared = starter.prepare("mainnet", dir.toString(), args("--toolkit-snapshot-url=$bad"))
            assertIs<OpstackNodeProcessStarter.Prepared.Failed>(prepared, bad)
        }
        assertFalse(Files.exists(dir.resolve("bin")), "nothing is written for a refused URL")
    }

    @Test
    fun a_signed_url_with_a_query_and_every_supported_suffix_is_accepted()
    {
        val dir = Files.createTempDirectory("opstack-start-ok")
        for (ok in listOf(
            "https://a.example/d.tar", "http://a.example/d.tar.gz", "https://a.example/d.tgz", "https://a.example/d.tar.lz4",
            "https://a.example/d.tar.zst?X-Amz-Signature=ab12&X-Amz-Expires=3600",
        ))
        {
            assertIs<OpstackNodeProcessStarter.Prepared.Ok>(starter.prepare("mainnet", dir.toString(), args("--toolkit-snapshot-url=$ok")), ok)
        }
    }

    @Test
    fun a_missing_node_dir_fails()
    {
        val prepared = starter.prepare("mainnet", "/definitely/not/here", args())
        assertEquals("node_dir missing: /definitely/not/here", assertIs<OpstackNodeProcessStarter.Prepared.Failed>(prepared).detail)
    }

    @Test
    fun role_dirs_come_from_the_launch_args_and_relative_or_traversing_ones_are_ignored()
    {
        val dir = Files.createTempDirectory("opstack-start-roles")
        val exec = Files.createTempDirectory("opstack-exec")
        val ok = assertIs<OpstackNodeProcessStarter.Prepared.Ok>(
            starter.prepare("sepolia", dir.toString(), args("--toolkit-execution=$exec", "--toolkit-consensus=rel/dir", "--toolkit-snapshots=/x/../etc")),
        )
        val body = Files.readString(ok.script)
        assertTrue(body.contains("EXEC='${exec.toAbsolutePath().normalize()}'"))
        assertTrue(body.contains("CONS='${dir.toAbsolutePath().normalize()}/consensus'"))
        assertTrue(body.contains("SNAPS='${dir.toAbsolutePath().normalize()}/snapshots'"))
        assertEquals("sepolia", ok.envId)
    }
}
