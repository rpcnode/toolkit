package rpcnode.toolkit.panel.presentation.http

import kotlinx.serialization.Serializable

@Serializable
data class HealthzResponse(
    val ok: Boolean,
    val alive: Boolean,
    val role: String,
    val port: Int,
    val db: String,
)
