package rpcnode.toolkit.chains.stellar.infrastructure

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StellarCaptiveCoreConfigTest
{
    private val cfg = "HTTP_PORT=11626\nPEER_PORT=11725\nNETWORK_PASSPHRASE=\"Public\"\n\n[[HOME_DOMAINS]]\nHOME_DOMAIN=\"a\"\n\n[[VALIDATORS]]\nNAME=\"x\"\nHTTP_PORT=9999\nPEER_PORT=8888\n"

    @Test
    fun ports_are_replaced_at_the_root_before_the_first_table()
    {
        val out = StellarCaptiveCoreConfig.applyPorts(cfg, 42562)
        assertFalse("HTTP_PORT" in out)
        assertTrue("PEER_PORT=42562\n" in out)
        assertFalse("PEER_PORT=8888" in out || "PEER_PORT=11725" in out)
        assertTrue(out.indexOf("PEER_PORT=42562") < out.indexOf("[[HOME_DOMAINS]]"))
        assertTrue("NETWORK_PASSPHRASE=\"Public\"" in out && "[[VALIDATORS]]" in out)
    }

    @Test
    fun without_tables_the_port_is_appended_and_zero_only_strips()
    {
        assertTrue(StellarCaptiveCoreConfig.applyPorts("A=1", 42562).endsWith("A=1\nPEER_PORT=42562\n"))
        val stripped = StellarCaptiveCoreConfig.applyPorts(cfg, 0)
        assertFalse("PEER_PORT" in stripped || "HTTP_PORT" in stripped)
    }
}
