package rpcnode.toolkit.catalog.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import rpcnode.toolkit.catalog.domain.Chain
import rpcnode.toolkit.catalog.domain.Env
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkCatalog
import rpcnode.toolkit.catalog.domain.NetworkId

class LookupNetworkUseCaseTest
{
    private val catalog = FixedCatalog(
        Chain(
            id = NetworkId.BITCOIN,
            label = "Bitcoin",
            envs = listOf(
                Env(id = EnvId.MAINNET, displayName = "Bitcoin Mainnet"),
                Env(id = EnvId.TESTNET4, displayName = "Bitcoin Testnet4"),
                Env(id = EnvId.SIGNET, displayName = "Bitcoin Signet"),
                Env(id = EnvId.REGTEST, displayName = "Bitcoin Regtest"),
            ),
        ),
    )
    private val lookupNetwork = LookupNetworkUseCase(catalog)
    private val lookupEnv = LookupNetworkEnvUseCase(catalog)

    @Test
    fun lookup_by_network_id()
    {
        val tests = listOf(
            "bitcoin" to NetworkId.BITCOIN,
            "Bitcoin" to NetworkId.BITCOIN,
            "  BITCOIN  " to NetworkId.BITCOIN,
        )
        for ((give, want) in tests)
        {
            assertEquals(want, lookupNetwork(give)?.id, "lookup($give)")
        }
    }

    @Test
    fun lookup_rejects_empty_and_port()
    {
        val tests = listOf("", "   ", "39290", "8332", "unknown")
        for (give in tests)
        {
            assertNull(lookupNetwork(give), "lookup($give) must not infer a chain")
        }
    }

    @Test
    fun lookupEnv_needs_network()
    {
        assertNull(lookupEnv("", "mainnet"))
        assertEquals(EnvId.MAINNET, lookupEnv("bitcoin", "")?.id)
        assertEquals(EnvId.TESTNET4, lookupEnv("bitcoin", "testnet4")?.id)
        assertNull(lookupEnv("bitcoin", "nile"))
    }
}

private class FixedCatalog(private val chain: Chain) : NetworkCatalog
{
    override fun find(id: NetworkId): Chain? = chain.takeIf { it.id == id }
    override fun all(): List<Chain> = listOf(chain)
}
