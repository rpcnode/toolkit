package rpcnode.toolkit.chains.evmfamily.infrastructure.http

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotSentinel

class EvmSnapshotRunnerTest
{
    private val runner = EvmSnapshotRunner()

    @Test
    fun writes_an_executable_script_for_a_network_that_has_one()
    {
        val root = Files.createTempDirectory("evm-snap")
        val ref = EvmSnapshotSentinel.parse("evm-snapshot://tempo/mainnet/archive")!!
        val profile = assertNotNull(runner.profileFor(ref))
        val paths = runner.layout(root, mapOf("tempo" to root.resolve("bin/tempo")))
        val script = assertNotNull(runner.writeScript(profile, ref, paths))
        val text = Files.readString(script)
        assertTrue(text.startsWith("#!/bin/bash") && "state()" in text && "download()" in text)
        assertTrue("systemctl stop rpcnode-tempo-mainnet.service" in text || "rpcnode-tempo-mainnet.service" in text)
        assertFalse("{{" in text)
        assertTrue(Files.isExecutable(script))
        assertEquals(root.resolve(".toolkit/evm-snapshot.done"), paths.marker)
    }

    @Test
    fun a_network_without_a_script_gets_none_and_an_unknown_network_no_profile()
    {
        val root = Files.createTempDirectory("evm-snap")
        val gnosis = runner.profileFor(EvmSnapshotRef("gnosis", "mainnet", "x"))!!
        assertNull(runner.writeScript(gnosis, EvmSnapshotRef("gnosis", "mainnet", "x"), runner.layout(root)))
        assertNull(runner.profileFor(EvmSnapshotRef("nope", "mainnet", "x")))
    }

    @Test
    fun reads_the_script_state_and_reports_its_error()
    {
        val root = Files.createTempDirectory("evm-snap")
        val paths = runner.layout(root)
        assertNull(runner.readState(paths.state))
        Files.createDirectories(paths.state.parent)
        Files.writeString(paths.state, """{"phase":"download","pct":42,"detail":"reth snapshot: 10 of 54 MiB","updated_at":"2026-10-14T00:00:00Z"}""")
        val s = assertNotNull(runner.readState(paths.state))
        assertEquals(42.0, s.pct)
        assertEquals("download", s.phase)
        assertNull(runner.failureMessage(paths.state))
        Files.writeString(paths.state, """{"phase":"error","pct":0,"detail":"genesis md5 mismatch","error":"genesis md5 mismatch","updated_at":"x"}""")
        assertEquals("genesis md5 mismatch", runner.failureMessage(paths.state))
        assertTrue(runner.readLiveProgress(paths)!!.phase == "error")
        assertFalse(runner.markerReady(paths.marker))
    }

    @Test
    fun berachain_script_carries_the_resolved_pair_quoted()
    {
        val root = Files.createTempDirectory("evm-snap")
        val ref = EvmSnapshotRef(
            "berachain", "mainnet", "pruned",
            mapOf("el" to "https://s/x'y.tar.lz4", "elsha" to "aa", "cl" to "https://s/c.tar.lz4", "clsha" to "bb", "block" to "27428241x"),
        )
        val profile = runner.profileFor(ref)!!
        val script = Files.readString(runner.writeScript(profile, ref, runner.layout(root))!!)
        assertTrue("EL_URL='https://s/x'\\''y.tar.lz4'" in script, "single quotes in a URL are escaped")
        assertTrue("BLOCK='27428241'" in script)
        assertTrue("rpcnode-berachain-consensus-mainnet.service rpcnode-berachain-mainnet.service" in script)
    }

    @Test
    fun every_scripted_snapshot_is_valid_bash()
    {
        val refs = listOf(
            EvmSnapshotRef("sonic", "mainnet", "pruned"),
            EvmSnapshotRef("tempo", "moderato", "minimal"),
            EvmSnapshotRef("fluent", "testnet", "official"),
            EvmSnapshotRef(
                "berachain", "mainnet", "archive",
                mapOf("el" to "https://s/e.tar.lz4", "elsha" to "aa", "cl" to "https://s/c.tar.lz4", "clsha" to "bb", "block" to "1"),
            ),
        )
        for (ref in refs)
        {
            val root = Files.createTempDirectory("evm-snap")
            val profile = runner.profileFor(ref)!!
            val bins = profile.recipe.binaries(profile.env(ref.env)).associate { it.name to root.resolve("bin/${it.name}") }
            val script = runner.writeScript(profile, ref, runner.layout(root, bins))!!
            val p = ProcessBuilder("bash", "-n", script.toString()).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, p.waitFor(), "${ref.network}: $out")
        }
    }
}
