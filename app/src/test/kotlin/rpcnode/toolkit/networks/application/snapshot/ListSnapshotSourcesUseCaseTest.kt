package rpcnode.toolkit.networks.application.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.base.infrastructure.BaseClusters
import rpcnode.toolkit.chains.base.infrastructure.http.BaseSnapshotResolver
import rpcnode.toolkit.chains.base.infrastructure.http.BaseSnapshotTipProbe
import rpcnode.toolkit.networks.domain.model.SnapshotArchive
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.settings.FakeSettingsStore
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin

class ListSnapshotSourcesUseCaseTest
{
    @Test
    fun lists_official_and_cdn_with_availability() = runTest {
        val officialUrl = "https://mirror.example/backup20260808/FullNode_output-directory.tgz"
        val store = FakeSettingsStore()
        store.setSnapshotCdnOrigin(okCdn("http://cdn.example:8095"))
        val probe = object : CdnMirrorProbe
        {
            override suspend fun versionText(url: String): String? = "backup20260101"
            override suspend fun archivePresent(url: String): Boolean = true
        }
        val useCase = ListSnapshotSourcesUseCase(
            resolve = ResolveSnapshotUseCase(
                YamlNetworkFactsRepository(),
                mapOf(NetworkId.TRON to resolver(officialUrl)),
            ),
            store = store,
            probe = probe,
            envSnapshotCdnOrigin = null,
        )

        val result = assertIs<SnapshotSourcesResult.Resolved>(useCase("tron", "mainnet"))
        assertEquals(2, result.sources.size)
        val official = result.sources.first { it.id == PreferCdnSnapshotUseCase.SOURCE_OFFICIAL }
        val cdn = result.sources.first { it.id == PreferCdnSnapshotUseCase.SOURCE_CDN }
        assertTrue(official.available)
        assertEquals(officialUrl, official.url)
        assertTrue(cdn.available)
        assertEquals("backup20260101", cdn.version)
        assertEquals(PreferCdnSnapshotUseCase.SOURCE_OFFICIAL, result.defaultSourceId)
    }

    @Test
    fun base_lists_official_and_cdn_manifest_when_published() = runTest {
        val store = FakeSettingsStore()
        store.setSnapshotCdnOrigin(okCdn("http://cdn.example:8095"))
        val tipVersion = "1788307205"
        val probe = object : CdnMirrorProbe
        {
            override suspend fun versionText(url: String): String?
            {
                assertTrue(url.endsWith("/snapshots/base/mainnet/archive/VERSION"))
                return tipVersion
            }

            override suspend fun archivePresent(url: String): Boolean
            {
                assertTrue(url.endsWith("/snapshots/base/mainnet/archive/$tipVersion/manifest.json"))
                return true
            }
        }
        val tip = BaseSnapshotTipProbe { env, profile ->
            assertEquals("mainnet", BaseClusters.lookup(env).env)
            assertEquals("archive", profile)
            BaseSnapshotTipProbe.Tip(
                version = tipVersion,
                manifestUrl = "https://mainnet-v2-snapshots.base.org/$tipVersion/manifest.json",
                sizeBytes = 100L,
            )
        }
        val useCase = ListSnapshotSourcesUseCase(
            resolve = ResolveSnapshotUseCase(
                YamlNetworkFactsRepository(),
                mapOf(NetworkId.BASE to BaseSnapshotResolver()),
            ),
            store = store,
            probe = probe,
            envSnapshotCdnOrigin = null,
            baseTip = tip,
        )

        val result = assertIs<SnapshotSourcesResult.Resolved>(useCase("base", "mainnet", "full"))
        assertEquals(2, result.sources.size)
        val official = result.sources.first { it.id == PreferCdnSnapshotUseCase.SOURCE_OFFICIAL }
        val cdn = result.sources.first { it.id == PreferCdnSnapshotUseCase.SOURCE_CDN }
        assertTrue(official.available)
        assertTrue(cdn.available)
        assertEquals(tipVersion, cdn.version)
        assertEquals(tipVersion, result.officialVersion)
        assertEquals(
            "http://cdn.example:8095/snapshots/base/mainnet/archive/$tipVersion/manifest.json?env=mainnet&flavor=full",
            cdn.url,
        )
        assertEquals(PreferCdnSnapshotUseCase.SOURCE_CDN, result.defaultSourceId)
    }

    private fun resolver(url: String) = SnapshotResolver { env, _ ->
        if (env == EnvId.MAINNET)
        {
            SnapshotArchive(url = url, streamUnpack = true, sizeBytes = 100)
        }
        else
        {
            null
        }
    }

    private fun okCdn(value: String): SnapshotCdnOrigin =
        (SnapshotCdnOrigin.parse(value) as SnapshotCdnOrigin.Parse.Ok).origin
}
