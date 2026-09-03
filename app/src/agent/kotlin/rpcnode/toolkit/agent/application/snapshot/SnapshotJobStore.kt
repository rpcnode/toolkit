package rpcnode.toolkit.agent.application.snapshot

import rpcnode.toolkit.agent.domain.model.SnapshotJob

interface SnapshotJobStore
{
    fun read(jobId: String): SnapshotJob?
    fun write(job: SnapshotJob)
    fun isRunning(jobId: String): Boolean
    fun list(): List<SnapshotJob>
}
