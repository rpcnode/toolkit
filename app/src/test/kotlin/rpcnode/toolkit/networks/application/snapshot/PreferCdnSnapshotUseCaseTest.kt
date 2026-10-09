package rpcnode.toolkit.networks.application.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.SnapshotArchive
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.settings.FakeSettingsStore
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin

class PreferCdnSnapshotUseCaseTest
{
    @Test
    fun prefers_cdn_when_version_matches_and_archive_present() = runTest {
        val officialUrl = "https://mirror.example/backup20260808/FullNode_output-directory.tgz"
        val store = FakeSettingsStore()
        store.setSnapshotCdnOrigin(okCdn("http://cdn.example:8095"))
        val probe = matchingProbe("backup20260808")
        val useCase = prefer(store, probe, mapOf(NetworkId.TRON to resolver(officialUrl)))

        val result = assertIs<PreferCdnSnapshotResult.Resolved>(useCase("tron", "mainnet"))
        assertEquals("http://cdn.example:8095/snapshots/tron/mainnet/full/FullNode_output-directory.tgz", result.url)
        assertEquals(officialUrl, result.officialUrl)
        assertEquals("backup20260808", result.version)
        assertEquals(PreferCdnSnapshotUseCase.SOURCE_CDN, result.source)
        assertEquals(true, result.streamUnpack)
    }

    @Test
    fun auto_falls_back_when_cdn_version_differs() = runTest {
        val officialUrl = "https://mirror.example/backup20260808/FullNode_output-directory.tgz"
        val store = FakeSettingsStore()
        store.setSnapshotCdnOrigin(okCdn("http://cdn.example:8095"))
        val probe = object : CdnMirrorProbe
        {
            override suspend fun versionText(url: String): String? = "backup20260101"
            override suspend fun archivePresent(url: String): Boolean = true
        }
        val useCase = prefer(store, probe, mapOf(NetworkId.TRON to resolver(officialUrl)))

        val result = assertIs<PreferCdnSnapshotResult.Resolved>(useCase("tron", "mainnet"))
        assertEquals(officialUrl, result.url)
        assertEquals(PreferCdnSnapshotUseCase.SOURCE_OFFICIAL, result.source)
        assertEquals("backup20260808", result.version)
    }

    @Test
    fun explicit_cdn_uses_mirror_even_when_version_differs() = runTest {
        val officialUrl = "https://mirror.example/backup20260808/FullNode_output-directory.tgz"
        val store = FakeSettingsStore()
        store.setSnapshotCdnOrigin(okCdn("http://cdn.example:8095"))
        val probe = object : CdnMirrorProbe
        {
            override suspend fun versionText(url: String): String? = "backup20260101"
            override suspend fun archivePresent(url: String): Boolean = true
        }
        val useCase = prefer(store, probe, mapOf(NetworkId.TRON to resolver(officialUrl)))

        val result = assertIs<PreferCdnSnapshotResult.Resolved>(
            useCase("tron", "mainnet", source = "cdn"),
        )
        assertEquals("http://cdn.example:8095/snapshots/tron/mainnet/full/FullNode_output-directory.tgz", result.url)
        assertEquals("backup20260101", result.version)
        assertEquals(PreferCdnSnapshotUseCase.SOURCE_CDN, result.source)
    }

    @Test
    fun source_official_skips_cdn() = runTest {
        val officialUrl = "https://mirror.example/backup20260808/FullNode_output-directory.tgz"
        val store = FakeSettingsStore()
        store.setSnapshotCdnOrigin(okCdn("http://cdn.example:8095"))
        val probe = matchingProbe("backup20260808")
        val useCase = prefer(store, probe, mapOf(NetworkId.TRON to resolver(officialUrl)))

        val result = assertIs<PreferCdnSnapshotResult.Resolved>(
            useCase("tron", "mainnet", source = "official"),
        )
        assertEquals(officialUrl, result.url)
        assertEquals(PreferCdnSnapshotUseCase.SOURCE_OFFICIAL, result.source)
    }

    @Test
    fun empty_cdn_origin_keeps_official() = runTest {
        val officialUrl = "https://mirror.example/backup20260808/FullNode_output-directory.tgz"
        val useCase = prefer(
            FakeSettingsStore(),
            matchingProbe("backup20260808"),
            mapOf(NetworkId.TRON to resolver(officialUrl)),
        )
        val result = assertIs<PreferCdnSnapshotResult.Resolved>(useCase("tron", "mainnet"))
        assertEquals(officialUrl, result.url)
        assertEquals(PreferCdnSnapshotUseCase.SOURCE_OFFICIAL, result.source)
    }

    @Test
    fun null_archive_returns_empty_official() = runTest {
        val useCase = prefer(
            FakeSettingsStore(),
            object : CdnMirrorProbe
            {
                override suspend fun versionText(url: String): String? = null
                override suspend fun archivePresent(url: String): Boolean = false
            },
            mapOf(NetworkId.TRON to SnapshotResolver { _, _ -> null }),
        )
        val result = assertIs<PreferCdnSnapshotResult.Resolved>(useCase("tron", "mainnet"))
        assertNull(result.url)
        assertNull(result.version)
        assertEquals(PreferCdnSnapshotUseCase.SOURCE_OFFICIAL, result.source)
    }

    @Test
    fun explicit_unavailable_cdn_returns_source_unavailable() = runTest {
        val officialUrl = "https://mirror.example/backup20260808/FullNode_output-directory.tgz"
        val store = FakeSettingsStore()
        store.setSnapshotCdnOrigin(okCdn("http://cdn.example:8095"))
        val probe = object : CdnMirrorProbe
        {
            override suspend fun versionText(url: String): String? = "backup20260101"
            override suspend fun archivePresent(url: String): Boolean = false
        }
        val useCase = prefer(store, probe, mapOf(NetworkId.TRON to resolver(officialUrl)))

        val result = assertIs<PreferCdnSnapshotResult.SourceUnavailable>(
            useCase("tron", "mainnet", source = "cdn"),
        )
        assertEquals(PreferCdnSnapshotUseCase.SOURCE_CDN, result.source)
    }

    private fun matchingProbe(version: String) = object : CdnMirrorProbe
    {
        override suspend fun versionText(url: String): String? =
            if (url.endsWith("/VERSION")) version else null

        override suspend fun archivePresent(url: String): Boolean =
            url.endsWith("/FullNode_output-directory.tgz")
    }

    private fun prefer(
        store: FakeSettingsStore,
        probe: CdnMirrorProbe,
        resolvers: Map<NetworkId, SnapshotResolver>,
    ): PreferCdnSnapshotUseCase
    {
        val list = ListSnapshotSourcesUseCase(
            resolve = ResolveSnapshotUseCase(YamlNetworkFactsRepository(), resolvers),
            store = store,
            probe = probe,
            envSnapshotCdnOrigin = null,
        )
        return PreferCdnSnapshotUseCase(list)
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
