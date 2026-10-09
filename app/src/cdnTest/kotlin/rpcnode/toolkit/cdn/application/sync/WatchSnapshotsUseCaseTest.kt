package rpcnode.toolkit.cdn.application.sync

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.cdn.infrastructure.filesystem.DiskSnapshotMirrorStore
import rpcnode.toolkit.cdn.infrastructure.filesystem.FileSnapshotTargetStore
import rpcnode.toolkit.cdn.presentation.CdnMenu

@OptIn(ExperimentalCoroutinesApi::class)
class WatchSnapshotsUseCaseTest
{
    @Test
    fun skips_when_version_matches() = runTest {
        val store = MemoryMirrorStore().also {
            it.versions["tron/mainnet/full"] = "backup20260808"
        }
        val watch = WatchSnapshotsUseCase(
            source = FakeSource(listOf(SnapshotTarget("tron", "mainnet"))),
            store = store,
            scope = backgroundScope,
            idleSec = 60,
        )
        watch.tick()
        runCurrent()
        assertEquals(0, store.publishCount)
        assertEquals(setOf("tron/mainnet/full"), watch.activeIds())
    }

    @Test
    fun publishes_when_version_differs() = runTest {
        val store = MemoryMirrorStore()
        val watch = WatchSnapshotsUseCase(
            source = FakeSource(listOf(SnapshotTarget("tron", "mainnet"))),
            store = store,
            scope = backgroundScope,
            idleSec = 60,
        )
        watch.tick()
        runCurrent()
        assertEquals(1, store.publishCount)
        assertEquals("backup20260808", store.versions["tron/mainnet/full"])
        assertEquals("2026-08-08", store.index.single().date)
        assertEquals("full", store.index.single().type)
    }

    @Test
    fun second_tick_does_not_start_the_same_download() = runTest {
        val store = MemoryMirrorStore()
        val watch = WatchSnapshotsUseCase(
            source = FakeSource(listOf(SnapshotTarget("tron", "mainnet"))),
            store = store,
            scope = backgroundScope,
            idleSec = 60,
        )
        watch.tick()
        runCurrent()
        watch.tick()
        runCurrent()
        assertEquals(1, store.publishCount)
        assertEquals(setOf("tron/mainnet/full"), watch.activeIds())
    }

    @Test
    fun publishes_two_envs_in_parallel() = runTest {
        val store = MemoryMirrorStore()
        val watch = WatchSnapshotsUseCase(
            source = FakeSource(
                listOf(SnapshotTarget("tron", "mainnet"), SnapshotTarget("tron", "nile")),
            ),
            store = store,
            scope = backgroundScope,
            jobs = 2,
            idleSec = 60,
        )
        watch.tick()
        runCurrent()
        assertEquals(2, store.publishCount)
        assertEquals(setOf("tron/mainnet/full", "tron/nile/full"), store.versions.keys)
        assertEquals(setOf("tron/mainnet/full", "tron/nile/full"), watch.activeIds())
    }

    @Test
    fun targets_unavailable_keeps_running_workers() = runTest {
        val store = MemoryMirrorStore()
        val source = FakeSource(listOf(SnapshotTarget("tron", "mainnet")))
        val watch = WatchSnapshotsUseCase(source, store, scope = backgroundScope, idleSec = 60)
        watch.tick()
        runCurrent()
        assertEquals(setOf("tron/mainnet/full"), watch.activeIds())
        source.targets = null
        watch.tick()
        runCurrent()
        assertEquals(setOf("tron/mainnet/full"), watch.activeIds())
        assertEquals(1, store.publishCount)
    }

    @Test
    fun removed_network_stops_its_worker() = runTest {
        val store = MemoryMirrorStore()
        val source = FakeSource(listOf(SnapshotTarget("tron", "mainnet")))
        val watch = WatchSnapshotsUseCase(source, store, scope = backgroundScope, idleSec = 60)
        watch.tick()
        runCurrent()
        source.targets = emptyList()
        watch.tick()
        runCurrent()
        assertEquals(emptySet(), watch.activeIds())
    }

    @Test
    fun dropped_download_retries_once() = runTest {
        val store = MemoryMirrorStore().also { it.failNextPublish = 1 }
        val watch = WatchSnapshotsUseCase(
            source = FakeSource(listOf(SnapshotTarget("tron", "mainnet"))),
            store = store,
            scope = backgroundScope,
            idleSec = 60,
            retrySec = 15,
        )
        watch.tick()
        runCurrent()
        assertEquals(0, store.publishCount)
        advanceTimeBy(15_000)
        runCurrent()
        assertEquals(1, store.publishCount)
        assertEquals("backup20260808", store.versions["tron/mainnet/full"])
    }

    @Test
    fun date_from_backup_version()
    {
        assertEquals("2026-08-08", snapshotDateFromVersion("backup20260808"))
        assertEquals("other", snapshotDateFromVersion("other"))
    }

    @Test
    fun disk_store_reads_version_and_describe() = runTest {
        val dir = Files.createTempDirectory("cdn-mirror")
        val store = DiskSnapshotMirrorStore(dir)
        assertNull(store.currentVersion("tron", "mainnet", "full"))
        assertNull(store.describe("tron", "mainnet", "full"))
        val env = dir.resolve("snapshots/tron/mainnet/full")
        Files.createDirectories(env)
        Files.writeString(env.resolve("VERSION"), "backup20260808\n")
        Files.writeString(
            env.resolve("manifest.json"),
            """{"network":"tron","env":"mainnet","type":"full","version":"backup20260808","filename":"FullNode_output-directory.tgz","size_bytes":10,"official_url":"https://x","updated_at":"2026-09-01T12:00:00Z","path":"tron/mainnet/full/FullNode_output-directory.tgz"}""",
        )
        assertEquals("backup20260808", store.currentVersion("tron", "mainnet", "full"))
        val entry = store.describe("tron", "mainnet", "full")!!
        assertEquals("2026-08-08", entry.date)
        assertEquals("FullNode_output-directory.tgz", entry.filename)
        assertEquals(10L, entry.sizeBytes)
        assertEquals("full", entry.type)
        assertEquals("tron/mainnet/full/FullNode_output-directory.tgz", entry.path)
        assertEquals("2026-09-01T12:00:00Z", entry.updatedAt)

        store.writeIndex(listOf(entry))
        val index = Files.readString(dir.resolve("snapshots/index.json"))
        assertTrue(index.contains("tron/mainnet/full/FullNode_output-directory.tgz"))
        assertTrue(index.contains("generated_at"))

        // site.json + rebuild under lock keeps all cards
        Files.writeString(
            env.resolve("site.json"),
            """{"network":"tron","env":"mainnet","type":"full","version":"backup20260808","date":"2026-08-08","size_bytes":10,"filename":"FullNode_output-directory.tgz","path":"tron/mainnet/full/FullNode_output-directory.tgz","updated_at":"2026-09-01T12:00:00Z"}""",
        )
        val nile = dir.resolve("snapshots/tron/nile/full")
        Files.createDirectories(nile)
        Files.writeString(
            nile.resolve("site.json"),
            """{"network":"tron","env":"nile","type":"full","version":"backup20260901","date":"2026-09-01","size_bytes":20,"filename":"FullNode_output-directory.tgz","path":"tron/nile/full/FullNode_output-directory.tgz","updated_at":"2026-09-01T13:00:00Z"}""",
        )
        store.rebuildPublicIndex()
        val rebuilt = store.listPublished()
        assertEquals(2, rebuilt.size)
        assertEquals(setOf("tron/mainnet/full", "tron/nile/full"), rebuilt.map { "${it.network}/${it.env}/${it.type}" }.toSet())
    }

    @Test
    fun target_store_and_menu_add_remove()
    {
        val dir = Files.createTempDirectory("cdn-targets")
        val file = dir.resolve("targets.json")
        val store = FileSnapshotTargetStore(file)
        // pick sequence: Add → tron → mainnet → full; Add → tron → nile → lite; Delete → first; Quit
        val picks = ArrayDeque(
            listOf(
                0, 0, 0, 0,
                0, 0, 1, 1,
                1, 0,
                4,
            ),
        )
        CdnMenu.run(
            store = store,
            envFile = dir.resolve("rpcnode-cdn.env"),
            snapshotDir = dir.toString(),
            pick = { _, _, _ -> picks.removeFirstOrNull() },
            waitContinue = {},
        )
        assertEquals(listOf(SnapshotTarget("tron", "nile", "lite")), store.list())
        assertTrue(Files.isRegularFile(file))
    }
}

private class FakeSource(
    var targets: List<SnapshotTarget>?,
    private val version: String = "backup20260808",
) : SnapshotSource
{
    override suspend fun listTargets() = targets

    override suspend fun officialSnapshot(target: SnapshotTarget) = OfficialSnapshot(
        network = target.network,
        env = target.env,
        type = target.type,
        url = "https://mirror.example/${target.env}/FullNode_output-directory.tgz",
        version = version,
        filename = "FullNode_output-directory.tgz",
        sizeBytes = 10,
    )
}

private class MemoryMirrorStore : SnapshotMirrorStore
{
    val versions = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val published = java.util.concurrent.atomic.AtomicInteger()
    val publishCount: Int get() = published.get()
    var index: List<MirrorEntry> = emptyList()
    var failNextPublish: Int = 0

    override suspend fun currentVersion(network: String, env: String, type: String): String? =
        versions["$network/$env/$type"]

    override suspend fun describe(network: String, env: String, type: String): MirrorEntry?
    {
        val version = versions["$network/$env/$type"] ?: return null
        return MirrorEntry(
            network = network,
            env = env,
            type = type,
            version = version,
            date = snapshotDateFromVersion(version),
            sizeBytes = null,
            filename = "",
        )
    }

    override suspend fun publish(
        network: String,
        env: String,
        type: String,
        version: String,
        filename: String,
        sourceUrl: String,
        sizeBytes: Long?,
    )
    {
        if (failNextPublish > 0)
        {
            failNextPublish -= 1
            error("connection reset")
        }
        published.incrementAndGet()
        versions["$network/$env/$type"] = version
        rebuildPublicIndex()
    }

    override suspend fun publishBaseManifest(
        network: String,
        env: String,
        type: String,
        version: String,
        manifestUrl: String,
        sizeBytes: Long?,
        publicOrigin: String,
    )
    {
        publish(
            network = network,
            env = env,
            type = type,
            version = version,
            filename = "manifest.json",
            sourceUrl = manifestUrl,
            sizeBytes = sizeBytes,
        )
    }

    override suspend fun rebuildPublicIndex()
    {
        index = versions.keys.mapNotNull { key ->
            val parts = key.split('/')
            if (parts.size != 3) return@mapNotNull null
            describe(parts[0], parts[1], parts[2])
        }.sortedWith(compareBy({ it.network }, { it.env }, { it.type }))
    }

    override suspend fun writeIndex(entries: List<MirrorEntry>)
    {
        index = entries
    }

    override suspend fun listPublished(): List<MirrorEntry> = index
}
