package rpcnode.toolkit.agent.application.enroll

fun interface ProbePanel
{
    /** GET `{panelUrl}/healthz` — the host must reach the panel origin. */
    suspend fun reachable(panelUrl: String): Boolean
}
