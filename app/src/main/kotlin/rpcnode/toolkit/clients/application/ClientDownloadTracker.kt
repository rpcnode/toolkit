package rpcnode.toolkit.clients.application

enum class ClientDownloadPhase { QUEUED, DOWNLOAD, DONE, FAIL }

data class ClientDownloadProgress(
    val phase: ClientDownloadPhase,
    val name: String = "",
    val bytes: Long = 0,
    val total: Long = 0,
    val error: String = "",
)

/**
 * In-process live download state, keyed by [ClientProgramKey]. There is no separate worker
 * process in this port (single JVM, coroutines only), so no sidecar files/Redis are needed.
 */
interface ClientDownloadTracker
{
    fun get(key: ClientProgramKey): ClientDownloadProgress?

    fun set(key: ClientProgramKey, progress: ClientDownloadProgress)

    fun clear(key: ClientProgramKey)
}
