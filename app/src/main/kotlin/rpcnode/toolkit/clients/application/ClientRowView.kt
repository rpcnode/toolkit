package rpcnode.toolkit.clients.application

import rpcnode.toolkit.clients.domain.model.ClientVersionPin

/** A pin plus its live download progress — only meaningful for the Add-client preview flow. */
data class ClientRowView(
    val pin: ClientVersionPin,
    val downloadPhase: String? = null,
    val downloadName: String? = null,
    val downloadBytes: Long? = null,
    val downloadTotal: Long? = null,
    val downloadPct: Double? = null,
    val downloadError: String? = null,
)
