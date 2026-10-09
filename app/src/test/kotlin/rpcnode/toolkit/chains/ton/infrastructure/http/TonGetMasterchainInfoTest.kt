package rpcnode.toolkit.chains.ton.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import rpcnode.toolkit.chains.ton.infrastructure.TonInstallOptions
import rpcnode.toolkit.chains.ton.infrastructure.TonUnitBodies
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.catalog.domain.NetworkId

class TonGetMasterchainInfoTest
{
    @Test
    fun parse_result_last_seqno()
    {
        val body = """
          {"ok":true,"result":{"last":{"workchain":-1,"shard":"-9223372036854775808","seqno":41234567,"root_hash":"ab","file_hash":"cd"}}}
        """.trimIndent()
        assertEquals(41_234_567L, TonGetMasterchainInfo.parseSeqno(body))
    }

    @Test
    fun parse_result_seqno_fallback()
    {
        val body = """{"ok":true,"result":{"seqno":99}}"""
        assertEquals(99L, TonGetMasterchainInfo.parseSeqno(body))
    }

    @Test
    fun parse_rejects_not_ok_without_seqno()
    {
        assertNull(TonGetMasterchainInfo.parseSeqno("""{"ok":false}"""))
    }

    @Test
    fun install_extra_and_capacity_literals_match_yaml()
    {
        assertEquals("-d", TonInstallOptions.installExtra("dump"))
        assertEquals("--archive", TonInstallOptions.installExtra("archive"))
        assertEquals(4_194_304, TonUnitBodies.VALIDATOR_NOFILE)
        assertEquals(8_388_608, TonUnitBodies.NR_OPEN)

        val facts = YamlNetworkFactsRepository().factsFor(NetworkId.TON)!!
        val bindings = facts.clientConfig!!.bindings.associate { it.path to it.value }
        assertEquals("4194304", bindings["LimitNOFILE"])
        assertEquals("2592000", bindings["archive_ttl"])
        assertEquals("86400", bindings["state_ttl"])
        assertEquals("8388608", bindings["fs.nr_open"])
        assertEquals(true, facts.oneEnvPerHost)
        assertEquals("never", facts.envs.single { it.id == "mainnet" }.snapshot)
    }
}
