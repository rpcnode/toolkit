package rpcnode.toolkit.chains.nonevm.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseAsset

class NonEvmReleasesTest
{
    private fun r(tag: String, pre: Boolean = false, vararg assets: String) =
        GitHubRelease(tag = tag, version = tag.removePrefix("v"), assets = assets.map { GitHubReleaseAsset(it, "") }, prerelease = pre)

    @Test
    fun parses_versions_with_up_to_five_parts_and_rc()
    {
        assertEquals(listOf(4L, 0L, 5L), NonEvmReleases.parse("4.0.5")!!.parts)
        assertEquals(listOf(3L, 4L, 0L, 0L, 4L), NonEvmReleases.parse("3.4.0.0.4")!!.parts)
        assertEquals(3L, NonEvmReleases.parse("2.14.0-rc.3")!!.rc)
        assertEquals(listOf(0L, 48L, 3L), NonEvmReleases.parse("v0.48.3", "v")!!.parts)
        assertNull(NonEvmReleases.parse("signer-3.4.0.0.4.0"))
        assertNull(NonEvmReleases.parse("testnet-v4.12.0", "v"))
        assertNull(NonEvmReleases.parse("v4.12.0"), "a prefix is required when the tag has one")
    }

    @Test
    fun orders_by_version_and_a_candidate_is_below_its_final()
    {
        assertEquals("4.0.5", NonEvmReleases.highest(listOf(r("4.0.4"), r("4.0.5"), r("3.4.0.0.4")))!!.tag)
        // fuel published v0.47.4 after v0.48.0
        assertEquals("v0.48.3", NonEvmReleases.highest(listOf(r("v0.47.4"), r("v0.48.3"), r("v0.48.0")), prefix = "v")!!.tag)
        val near = listOf(r("2.14.0-rc.3", true), r("2.13.4"), r("2.14.0-rc.1", true))
        assertEquals("2.13.4", NonEvmReleases.highest(near)!!.tag)
        assertEquals("2.14.0-rc.3", NonEvmReleases.highest(near, includePrerelease = true)!!.tag)
        assertEquals("2.14.0", NonEvmReleases.highest(near + r("2.14.0"), includePrerelease = true)!!.tag)
    }

    @Test
    fun requires_the_assets()
    {
        val list = listOf(r("v4.11.0", false, "aleo-v4.11.0-windows.zip"), r("v4.10.1", false, "aleo-v4.10.1-x86_64-unknown-linux-gnu.zip"))
        val got = NonEvmReleases.highest(list, prefix = "v", requiredAssets = { listOf("aleo-${it.tag}-x86_64-unknown-linux-gnu.zip") })
        assertEquals("v4.10.1", got!!.tag)
    }
}
