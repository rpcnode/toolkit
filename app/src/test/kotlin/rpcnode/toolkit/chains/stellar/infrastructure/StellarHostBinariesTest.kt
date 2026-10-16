package rpcnode.toolkit.chains.stellar.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StellarHostBinariesTest
{
    @Test
    fun protocol_is_read_from_the_version_output()
    {
        assertEquals(29, StellarHostBinaries.parseProtocol("stellar-core 29.0.0 (abc)\nledger protocol version: 29\n"))
        assertNull(StellarHostBinaries.parseProtocol("nothing here"))
    }

    @Test
    fun finds_a_binary_in_an_unpacked_deb_tree()
    {
        val dir = Files.createTempDirectory("stellar-deb")
        Files.createDirectories(dir.resolve("usr/bin"))
        Files.writeString(dir.resolve("usr/bin/stellar-core"), "x")
        assertNotNull(StellarHostBinaries.findBinary(dir, "stellar-core"))
        assertNull(StellarHostBinaries.findBinary(dir, "stellar-rpc"))
    }

    @Test
    fun arm_hosts_and_missing_debs_fail_with_a_reason()
    {
        val dir = Files.createTempDirectory("stellar-node")
        val arm = StellarHostBinaries.ensure(dir, arch = "aarch64") as StellarHostBinaries.Result.Failed
        assertTrue("no arm64 build" in arm.detail)
        val missing = StellarHostBinaries.ensure(dir, arch = "amd64") as StellarHostBinaries.Result.Failed
        assertTrue(".deb files missing" in missing.detail)
    }
}
