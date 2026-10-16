package rpcnode.toolkit.chains.aptos.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AptosHostBinariesTest
{
    @Test
    fun only_x86_64_hosts_are_supported()
    {
        assertTrue(AptosHostBinaries.archSupported("amd64") && AptosHostBinaries.archSupported("x86_64"))
        assertFalse(AptosHostBinaries.archSupported("aarch64") || AptosHostBinaries.archSupported("arm64"))
    }

    @Test
    fun extracts_the_binary_straight_into_bin_and_reports_a_missing_tarball()
    {
        val dir = Files.createTempDirectory("aptos-bin")
        val missing = AptosHostBinaries.ensure(dir, "amd64") as AptosHostBinaries.Result.Failed
        assertTrue("tarball missing" in missing.detail)
        assertTrue("x86_64 builds only" in (AptosHostBinaries.ensure(dir, "aarch64") as AptosHostBinaries.Result.Failed).detail)

        val staging = Files.createTempDirectory("aptos-stage")
        Files.writeString(staging.resolve("aptos-node"), "#!/bin/sh\necho ok\n")
        val tgz = dir.resolve("aptos-node-performance-ubuntu-22.04.tgz")
        val tar = ProcessBuilder("tar", "-czf", tgz.toString(), "-C", staging.toString(), "aptos-node").start()
        assertEquals(0, tar.waitFor())
        val ok = AptosHostBinaries.ensure(dir, "amd64") as AptosHostBinaries.Result.Ok
        assertEquals(dir.resolve("bin/aptos-node").toAbsolutePath().normalize(), ok.node)
        assertTrue(Files.isExecutable(ok.node))
        assertTrue(AptosHostBinaries.ensure(dir, "amd64") is AptosHostBinaries.Result.Ok)
    }
}
