package rpcnode.toolkit.clients.domain.model

/**
 * Latest client release for one env. How version/tag are found is per-network — a GitHub
 * releases feed, a pinned known-good build, a project-specific index. Callers ask one
 * network + env; they do not scrape themselves.
 */
data class ClientRelease(
    val version: String,
    val tag: String,
    val sourceLabel: String,
)
