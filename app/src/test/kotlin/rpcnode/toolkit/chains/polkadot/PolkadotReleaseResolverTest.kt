package rpcnode.toolkit.chains.polkadot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import rpcnode.toolkit.chains.polkadot.infrastructure.http.PolkadotClientReleaseResolver
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseAsset

class PolkadotReleaseResolverTest
{
    private val three = listOf("polkadot", "polkadot-prepare-worker", "polkadot-execute-worker", "polkadot.asc")
    private fun r(tag: String, assets: List<String> = three, pre: Boolean = false) =
        GitHubRelease(tag, tag, assets.map { GitHubReleaseAsset(it, "") }, prerelease = pre)

    @Test
    fun ranks_by_branch_and_patch_not_by_date()
    {
        // GitHub order (newest first), real tags of 2026-10-12: a back-patch of an old branch came last but is listed first
        val list = listOf(
            r("polkadot-stable2603-7", assets = listOf("x.sha256", "y.asc")),
            r("polkadot-stable2609"),
            r("polkadot-stable2606-3"),
            r("polkadot-stable2609-rc2", pre = true),
        )
        assertEquals("polkadot-stable2609", PolkadotClientReleaseResolver.pick(list)!!.tag)
        assertEquals("polkadot-stable2609-1", PolkadotClientReleaseResolver.pick(list + r("polkadot-stable2609-1"))!!.tag)
        assertEquals("polkadot-stable2606-3", PolkadotClientReleaseResolver.pick(list.drop(2).filter { it.tag != "polkadot-stable2609-rc2" })!!.tag)
    }

    @Test
    fun needs_all_three_binaries()
    {
        assertNull(PolkadotClientReleaseResolver.pick(listOf(r("polkadot-stable2609", assets = listOf("polkadot")))))
        assertNull(PolkadotClientReleaseResolver.rank("v1.0.0"))
        assertEquals(2609 to 0, PolkadotClientReleaseResolver.rank("polkadot-stable2609"))
        assertEquals(2603 to 7, PolkadotClientReleaseResolver.rank("polkadot-stable2603-7"))
    }
}
