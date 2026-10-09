package rpcnode.toolkit.chains.base.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class BaseL1ParentTest
{
    @Test
    fun public_defaults_for_mainnet_and_sepolia()
    {
        assertEquals(BaseL1Parent.MAINNET_RPC, BaseL1Parent.publicDefaults("mainnet")!!.rpc)
        assertEquals(BaseL1Parent.SEPOLIA_RPC, BaseL1Parent.publicDefaults("sepolia")!!.rpc)
    }

    @Test
    fun resolve_prefers_explicit_over_public()
    {
        val got = BaseL1Parent.resolve(
            "sepolia",
            rpcOverride = "http://127.0.0.1:8546",
            beaconOverride = "http://127.0.0.1:5053",
        )
        assertIs<BaseL1Parent.Result.Ok>(got)
        assertEquals("http://127.0.0.1:8546", got.endpoints.rpc)
        assertEquals("http://127.0.0.1:5053", got.endpoints.beacon)
    }

    @Test
    fun resolve_falls_back_to_public_when_no_override()
    {
        val got = BaseL1Parent.resolve("sepolia")
        assertIs<BaseL1Parent.Result.Ok>(got)
        assertEquals(BaseL1Parent.SEPOLIA_RPC, got.endpoints.rpc)
        assertEquals(BaseL1Parent.SEPOLIA_BEACON, got.endpoints.beacon)
    }
}
