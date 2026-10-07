package rpcnode.toolkit.cdn.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import rpcnode.toolkit.cdn.application.sync.SnapshotTarget
import rpcnode.toolkit.cdn.application.targets.SnapshotTargetStore
import rpcnode.toolkit.cdn.infrastructure.catalog.EmbeddedMirrorCatalog

class LocalSnapshotSourceTest
{
    @Test
    fun parse_base_api_fixture_to_official_snapshot()
    {
        val fixture = """
            [
              {
                "network": "mainnet",
                "profile": "archive",
                "manifestUrl": "https://mainnet-v2-snapshots.base.org/1788307205/manifest.json",
                "size": 4015007554913,
                "timestamp": 1788313960
              },
              {
                "network": "sepolia",
                "profile": "archive",
                "manifestUrl": "https://sepolia-v2-snapshots.base.org/1788307203/manifest.json",
                "size": 1039062997468,
                "timestamp": 1788309454
              }
            ]
        """.trimIndent()
        val source = LocalSnapshotSource(
            targets = object : SnapshotTargetStore
            {
                override fun list() = emptyList<SnapshotTarget>()
                override fun add(target: SnapshotTarget) {}
                override fun remove(id: String) = false
            },
            catalog = EmbeddedMirrorCatalog(),
        )
        val tip = source.parseBaseApiTip(fixture, network = "sepolia", profile = "archive")
        assertNotNull(tip)
        assertEquals(
            "https://sepolia-v2-snapshots.base.org/1788307203/manifest.json",
            tip.manifestUrl,
        )
        assertEquals(1039062997468L, tip.sizeBytes)
        assertEquals("1788307203", BaseManifestMirror.versionFromManifestUrl(tip.manifestUrl))
        assertNull(source.parseBaseApiTip(fixture, network = "sepolia", profile = "full"))
    }

    @Test
    fun catalog_ships_base_archive_targets()
    {
        val catalog = EmbeddedMirrorCatalog()
        val main = catalog.find("base", "mainnet", "archive")
        assertNotNull(main)
        assertEquals("base_api", main.discover)
        assertEquals("manifest.json", main.filename)
        assertEquals("https://chain.base.org/api/snapshots", main.mirror)
        assertNotNull(catalog.find("base", "sepolia", "archive"))
    }
}

class BaseManifestMirrorTest
{
    @Test
    fun rewrite_base_url_and_list_segments()
    {
        val upstream = """
            {
              "block": 1,
              "chain_id": 84532,
              "base_url": "https://sepolia-v2-snapshots.base.org",
              "components": {
                "state": {
                  "file": "1788307203/state.tar.zst",
                  "size": 10
                },
                "headers": {
                  "chunk_files": [
                    "static_files/headers-0-499999.tar.zst",
                    "static_files/headers-500000-999999.tar.zst"
                  ]
                }
              }
            }
        """.trimIndent()
        val rewritten = BaseManifestMirror.rewriteBaseUrl(
            upstream,
            "http://cdn.example:8095/snapshots/base/sepolia/archive",
        )
        assertEquals(
            "http://cdn.example:8095/snapshots/base/sepolia/archive",
            BaseManifestMirror.upstreamBaseUrl(rewritten),
        )
        assertEquals(
            listOf(
                "1788307203/state.tar.zst",
                "static_files/headers-0-499999.tar.zst",
                "static_files/headers-500000-999999.tar.zst",
            ),
            BaseManifestMirror.segmentRelativePaths(upstream),
        )
        assertEquals(
            "https://sepolia-v2-snapshots.base.org/static_files/headers-0-499999.tar.zst",
            BaseManifestMirror.joinUrl(
                "https://sepolia-v2-snapshots.base.org",
                "static_files/headers-0-499999.tar.zst",
            ),
        )
    }
}
