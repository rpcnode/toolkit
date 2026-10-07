package rpcnode.toolkit.chains.bitcore.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BitcoreRpcAuthTest
{
    @Test
    fun needs_rpc_user_for_forks_not_bitcoin()
    {
        assertTrue(BitcoreRpcAuth.needsRpcUserPassword("dash"))
        assertTrue(BitcoreRpcAuth.needsRpcUserPassword("LTC"))
        assertTrue(BitcoreRpcAuth.needsRpcUserPassword("doge"))
        assertTrue(BitcoreRpcAuth.needsRpcUserPassword("bch"))
        assertFalse(BitcoreRpcAuth.needsRpcUserPassword("bitcoin"))
        assertFalse(BitcoreRpcAuth.needsRpcUserPassword("tron"))
    }

    @Test
    fun preserve_from_conf_and_write_env()
    {
        val dir = Files.createTempDirectory("bitcore-rpc-auth")
        val conf =
            """
            server=1
            [main]
            rpcuser=kept
            rpcpassword=secret123
            """.trimIndent()
        val creds = BitcoreRpcAuth.ensure(dir, conf)
        assertEquals("kept", creds.user)
        assertEquals("secret123", creds.password)
        val env = Files.readString(BitcoreRpcAuth.authEnvPath(dir))
        assertTrue(env.contains("BITCOIN_RPC_USER=kept"))
        assertTrue(env.contains("BITCOIN_RPC_PASSWORD=secret123"))
    }

    @Test
    fun generate_when_missing_then_reuse_env()
    {
        val dir = Files.createTempDirectory("bitcore-rpc-gen")
        val first = BitcoreRpcAuth.ensure(dir, "# empty\n")
        assertEquals("rpcnode", first.user)
        assertTrue(first.password.length >= 32)
        val second = BitcoreRpcAuth.ensure(dir, "# still empty\n")
        assertEquals(first.user, second.user)
        assertEquals(first.password, second.password)
    }

    @Test
    fun upsert_env_keys_replaces_and_appends()
    {
        val raw = "RPCNODE_ENV=mainnet\nBITCOIN_RPC_USER=old\n"
        val next = BitcoreRpcAuth.upsertEnvKeys(
            raw,
            mapOf(
                "BITCOIN_RPC_USER" to "rpcnode",
                "BITCOIN_RPC_PASSWORD" to "newpass",
            ),
        )
        assertTrue(next.contains("BITCOIN_RPC_USER=rpcnode"))
        assertTrue(next.contains("BITCOIN_RPC_PASSWORD=newpass"))
        assertFalse(next.contains("BITCOIN_RPC_USER=old"))
        val path = Files.createTempDirectory("rpc-auth-read").resolve("rpc-auth.env")
        Files.writeString(path, next)
        val creds = BitcoreRpcAuth.readFromEnvFile(path)
        assertNotNull(creds)
        assertEquals("rpcnode", creds!!.user)
        assertEquals("newpass", creds.password)
    }
}
