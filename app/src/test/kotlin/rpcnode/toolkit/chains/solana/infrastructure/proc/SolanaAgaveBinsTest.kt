package rpcnode.toolkit.chains.solana.infrastructure.proc

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SolanaAgaveBinsTest
{
    @Test
    fun release_tag_normalizes_version()
    {
        assertEquals("v4.2.2", SolanaAgaveBins.releaseTag("4.2.2"))
        assertEquals("v4.2.2", SolanaAgaveBins.releaseTag("v4.2.2"))
    }

    @Test
    fun read_pinned_version_from_node_dir()
    {
        val dir = Files.createTempDirectory("solana-agave-bins")
        Files.writeString(dir.resolve("VERSION"), "4.2.2\n")
        assertEquals("4.2.2", SolanaAgaveBins.readPinnedVersion(dir))
    }

    @Test
    fun ensure_validator_uses_existing_node_dir_binary()
    {
        val nodeDir = Files.createTempDirectory("solana-node")
        val existing = SolanaAgaveBins.validatorPath(nodeDir)
        Files.createDirectories(existing.parent)
        Files.writeString(existing, "#!/bin/true\n")
        existing.toFile().setExecutable(true)

        val result = SolanaAgaveBins.ensureValidator(nodeDir)
        assertIs<SolanaAgaveBins.EnsureResult.Ok>(result)
        assertTrue(Files.isRegularFile(result.path) || Files.isSymbolicLink(result.path))
        assertTrue(result.path.startsWith(nodeDir))
    }

    @Test
    fun ensure_validator_fails_clearly_without_version_or_binary()
    {
        val nodeDir = Files.createTempDirectory("solana-empty")
        val result = SolanaAgaveBins.ensureValidator(nodeDir)
        assertIs<SolanaAgaveBins.EnsureResult.Failed>(result)
        assertTrue(result.detail.contains("Agave v3.0"), result.detail)
        assertTrue(result.detail.contains("VERSION") || result.detail.contains("source build"), result.detail)
        assertTrue(!result.detail.contains("/opt/solana"), result.detail)
    }

    @Test
    fun ensure_validator_reuses_var_tmp_build_after_node_dir_wipe()
    {
        val version = "9.9.9-test-reuse"
        val work = SolanaAgaveBins.workDirForVersion(version)
        val built = work.resolve("bin").resolve(SolanaAgaveBins.VALIDATOR)
        try
        {
            Files.createDirectories(built.parent)
            Files.writeString(built, "#!/bin/true\n")
            built.toFile().setExecutable(true)

            val nodeDir = Files.createTempDirectory("solana-reuse")
            Files.writeString(nodeDir.resolve("VERSION"), "$version\n")
            // Simulate sync wipe: only VERSION + tarball placeholders, no bin/agave-validator.
            val result = SolanaAgaveBins.ensureValidator(nodeDir)
            assertIs<SolanaAgaveBins.EnsureResult.Ok>(result)
            assertTrue(Files.isRegularFile(result.path) || Files.isSymbolicLink(result.path))
            assertTrue(result.path.endsWith(SolanaAgaveBins.VALIDATOR))
            assertTrue(Files.exists(SolanaAgaveBins.validatorPath(nodeDir)))
        }
        finally
        {
            runCatching { Files.deleteIfExists(built) }
            runCatching { Files.deleteIfExists(built.parent) }
            runCatching { Files.deleteIfExists(work) }
        }
    }

    @Test
    fun first_existing_skips_missing()
    {
        val nodeDir = Files.createTempDirectory("solana-cand")
        assertNull(SolanaAgaveBins.firstExisting(SolanaAgaveBins.nodeDirCandidates(nodeDir, SolanaAgaveBins.VALIDATOR)))
        val path = SolanaAgaveBins.validatorPath(nodeDir)
        Files.createDirectories(path.parent)
        Files.writeString(path, "x")
        assertNotNull(SolanaAgaveBins.firstExisting(SolanaAgaveBins.nodeDirCandidates(nodeDir, SolanaAgaveBins.VALIDATOR)))
    }
}
