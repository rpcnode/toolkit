package rpcnode.toolkit.agent.application.snapshot

import rpcnode.toolkit.agent.domain.model.SnapshotJob

interface SnapshotJobStore
{
    fun read(jobId: String): SnapshotJob?
    fun write(job: SnapshotJob)
    fun isRunning(jobId: String): Boolean

    /** A job file is on disk (even if it cannot be read — e.g. cut short by a power loss). */
    fun exists(jobId: String): Boolean = read(jobId) != null
    fun list(): List<SnapshotJob>
}
