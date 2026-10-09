package rpcnode.toolkit.servers.application.remove

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import rpcnode.toolkit.servers.domain.repository.ServerRepository

/** Picks up in-flight removals after a panel restart. Call from main(), not from HTTP module(). */
class ResumeServerRemovalsUseCase(
    private val servers: ServerRepository,
    private val finish: FinishRemoveServerUseCase,
    private val backgroundScope: CoroutineScope,
)
{
    operator fun invoke()
    {
        backgroundScope.launch {
            for (server in servers.listRemoving())
            {
                finish(server.id)
            }
        }
    }
}
