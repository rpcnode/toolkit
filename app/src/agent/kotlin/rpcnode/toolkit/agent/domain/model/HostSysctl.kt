package rpcnode.toolkit.agent.domain.model

/** Live `/proc/sys` values vs Anza-recommended Solana tuning (agent `GET /api/v1/host/sysctl`). */
data class HostSysctlSnapshot(
    val current: Map<String, Long?>,
    val recommended: Map<String, Long>,
    /** sysctl key → panel install_options key (Start Client config). */
    val installOptionKeys: Map<String, String>,
)
