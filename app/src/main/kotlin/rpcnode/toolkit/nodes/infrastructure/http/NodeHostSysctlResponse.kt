package rpcnode.toolkit.nodes.infrastructure.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NodeHostSysctlResponse(
    val ok: Boolean = true,
    val current: Map<String, Long?> = emptyMap(),
    val recommended: Map<String, Long> = emptyMap(),
    @SerialName("install_option_keys") val installOptionKeys: Map<String, String> = emptyMap(),
    val error: String? = null,
    val message: String? = null,
)
