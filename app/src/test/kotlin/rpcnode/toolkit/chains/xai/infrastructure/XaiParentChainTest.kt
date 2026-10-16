package rpcnode.toolkit.chains.xai.infrastructure

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XaiParentChainTest
{
    @Test
    fun option_wins_and_the_public_arbitrum_one_rpc_is_the_fallback()
    {
        assertEquals("http://127.0.0.1:8647", XaiParentChain.fromInstallOptions("""{"l1_rpc":" http://127.0.0.1:8647 "}"""))
        assertEquals(XaiChain.DEFAULT_PARENT_RPC, XaiParentChain.fromInstallOptions(""))
        assertEquals(XaiChain.DEFAULT_PARENT_RPC, XaiParentChain.fromInstallOptions("""{"l1_rpc":""}"""))
        assertEquals(XaiChain.DEFAULT_PARENT_RPC, XaiParentChain.fromInstallOptions("not json"))
    }

    @Test
    fun urls_that_could_break_out_of_the_env_file_line_are_refused()
    {
        assertNull(XaiParentChain.validate("https://arb-mainnet.g.alchemy.com/v2/abc_DEF-123"))
        assertNull(XaiParentChain.validate("http://127.0.0.1:8647"))
        for (bad in listOf("", "ws://x", "https://x y", "https://x\"; rm", "https://x\nA=b", "https://x\\y", "https://x/\$HOME", "https://x/%n", "ftp://x"))
        {
            assertNotNull(XaiParentChain.validate(bad), "should reject <$bad>")
        }
    }

    @Test
    fun env_file_is_owner_only_and_round_trips()
    {
        val dir = Files.createTempDirectory("xai-parent-")
        val file = XaiParentChain.writeEnvFile(dir, "https://arb.example/v2/SECRETKEY")
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
        assertTrue(Files.readString(file).contains("NITRO_PARENT__CHAIN_CONNECTION_URL=\"https://arb.example/v2/SECRETKEY\""))
        assertEquals("https://arb.example/v2/SECRETKEY", XaiParentChain.readEnvFile(dir))
        // a second write replaces the file and keeps the mode
        XaiParentChain.writeEnvFile(dir, "https://other.example")
        assertEquals("https://other.example", XaiParentChain.readEnvFile(dir))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file))
        assertNull(XaiParentChain.readEnvFile(Files.createTempDirectory("xai-none-")))
    }
}
