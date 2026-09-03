package rpcnode.toolkit.nodes.domain.model

/** Host sysctl snapshot from the server agent (`GET /api/v1/host/sysctl`). */
data class HostSysctlCatalog(
    val current: Map<String, Long?>,
    val recommended: Map<String, Long>,
    val installOptionKeys: Map<String, String>,
)
