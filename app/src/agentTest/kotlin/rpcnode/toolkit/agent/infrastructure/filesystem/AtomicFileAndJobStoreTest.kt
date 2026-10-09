package rpcnode.toolkit.agent.infrastructure.filesystem

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.agent.application.snapshot.GetSnapshotProgressUseCase
import rpcnode.toolkit.agent.domain.model.SnapshotJob

class AtomicFileAndJobStoreTest
{
    @Test
    fun atomic_write_creates_replaces_and_leaves_no_temp_files()
    {
        val dir = Files.createTempDirectory("atomic")
        val file = dir.resolve("sub").resolve("state.json")
        AtomicFile.writeString(file, "one")
        assertEquals("one", Files.readString(file))
        AtomicFile.writeString(file, "two, longer than before")
        assertEquals("two, longer than before", Files.readString(file))
        AtomicFile.writeString(file, "3")
        assertEquals("3", Files.readString(file))
        val leftovers = Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList() }
        assertEquals(listOf("state.json"), leftovers)
    }

    @Test
    fun job_store_round_trips_and_overwrites()
    {
        val store = FileSnapshotJobStore(Files.createTempDirectory("jobs"))
        store.write(SnapshotJob(jobId = "j1", url = "http://x", destDir = "/d", pct = 10.0, phase = "download"))
        store.write(SnapshotJob(jobId = "j1", url = "http://x", destDir = "/d", pct = 55.5, phase = "extract"))
        val job = store.read("j1")
        assertNotNull(job)
        assertEquals(55.5, job.pct)
        assertEquals("extract", job.phase)
        assertEquals(1, store.list().size)
    }

    @Test
    fun a_job_file_cut_short_by_a_power_loss_is_reported_not_silently_dropped()
    {
        val root = Files.createTempDirectory("jobs")
        val store = FileSnapshotJobStore(root)
        // exactly what a hard reboot in the middle of `Files.writeString` leaves behind
        Files.writeString(root.resolve("j2.json"), "")
        Files.writeString(root.resolve("j3.json"), """{"job_id":"j3","url":"http://x","dest""")

        assertNull(store.read("j2"))
        assertTrue(store.exists("j2"))
        assertTrue(store.exists("j3"))
        assertFalse(store.exists("missing"))

        val progress = GetSnapshotProgressUseCase(store)
        val damaged = progress("j2")
        assertNotNull(damaged)
        assertTrue(damaged.failed)
        assertEquals("failed", damaged.phase)
        assertTrue(damaged.error.contains("start the snapshot again"), damaged.error)
        assertTrue(progress("j3")!!.failed)
        assertNull(progress("never-existed"), "no file at all is still just 'no job'")
    }
}
