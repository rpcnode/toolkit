package rpcnode.toolkit.agent.application.client

/** Host → panel webhook for client-update milestones. */
fun interface NotifyPanelClientUpdate
{
    suspend operator fun invoke(
        panelUrl: String,
        token: String,
        serverId: String,
        nodeId: String,
        phase: String,
        step: String,
        detail: String,
        pct: Int,
        local: String,
        latest: String,
        previousVersion: String,
        updateAvailable: Boolean,
        lastError: String,
        logTail: String,
        eventId: String,
        eventLabel: String,
    ): Boolean
}
