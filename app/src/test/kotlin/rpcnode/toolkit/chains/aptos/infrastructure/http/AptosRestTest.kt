package rpcnode.toolkit.chains.aptos.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseAsset
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import kotlinx.coroutines.test.runTest

class AptosRestTest
{
    private val sample = """{"chain_id":1,"epoch":"17616","ledger_version":"7601542786","oldest_ledger_version":"7451742786","ledger_timestamp":"1791923238143563","node_role":"full_node","block_height":"1110785598"}"""

    @Test
    fun parses_the_ledger_and_rejects_garbage()
    {
        assertEquals(LedgerInfo(7601542786L, 1791923238143563L), AptosRest.parseLedger(sample))
        assertEquals(LedgerInfo(42, null), AptosRest.parseLedger("""{"ledger_version":42}"""))
        for (bad in listOf("""{"ledger_version":"abc"}""", """{"error_code":"x"}""", "", "null", "not json", null))
        {
            assertNull(AptosRest.parseLedger(bad), "bad=$bad")
        }
        assertEquals("http://127.0.0.1:41060/v1", AptosRest.ledgerUrl("http://127.0.0.1:41060/"))
        assertEquals("https://fullnode.mainnet.aptoslabs.com/v1", AptosRest.ledgerUrl("https://fullnode.mainnet.aptoslabs.com/v1"))
    }

    @Test
    fun syncing_means_the_latest_ledger_is_more_than_a_minute_old()
    {
        val now = 1_000_000_000_000L
        assertFalse(AptosNodeHeightProbe.toReading(LedgerInfo(100, now - 5_000_000), now).syncing)
        assertTrue(AptosNodeHeightProbe.toReading(LedgerInfo(100, now - 120_000_000), now).syncing)
        assertTrue(AptosNodeHeightProbe.toReading(LedgerInfo(0, 0), now).syncing)
        assertEquals(100L, AptosNodeHeightProbe.toReading(LedgerInfo(100, now), now).height)
    }

    private class Recording(val assets: List<GitHubReleaseAsset>) : GitHubReleaseClient
    {
        val asked = mutableListOf<Triple<String, String?, Boolean>>()
        override suspend fun latestRelease(repo: String, tagPrefix: String?): GitHubRelease? = latestRelease(repo, tagPrefix, false)
        override suspend fun latestRelease(repo: String, tagPrefix: String?, includePrerelease: Boolean): GitHubRelease?
        {
            asked += Triple(repo, tagPrefix, includePrerelease)
            return GitHubRelease(tag = "aptos-node-v1.49.6-hotfix", version = "aptos-node-v1.49.6-hotfix", assets = assets)
        }
    }

    @Test
    fun release_resolver_uses_the_node_tag_prefix_and_checks_the_asset() = runTest {
        val good = Recording(listOf(GitHubReleaseAsset("aptos-node-performance-ubuntu-22.04.tgz", "u")))
        val r = AptosClientReleaseResolver(good).resolve(EnvId.MAINNET)!!
        assertEquals("aptos-node-v1.49.6-hotfix", r.tag)
        assertEquals("1.49.6-hotfix", r.version)
        AptosClientReleaseResolver(good).resolve(EnvId.TESTNET)
        assertEquals(
            listOf(
                Triple("aptos-labs/aptos-core", "aptos-node-v" as String?, false),
                Triple("aptos-labs/aptos-core", "aptos-node-v" as String?, true),
            ),
            good.asked,
        )
        assertNull(AptosClientReleaseResolver(good).resolve(EnvId.FUJI))
        assertNull(AptosClientReleaseResolver(Recording(listOf(GitHubReleaseAsset("other.tgz", "u")))).resolve(EnvId.MAINNET))
        assertTrue(AptosClientReleaseResolver(Recording(emptyList())).resolve(EnvId.MAINNET) != null)
    }
}
