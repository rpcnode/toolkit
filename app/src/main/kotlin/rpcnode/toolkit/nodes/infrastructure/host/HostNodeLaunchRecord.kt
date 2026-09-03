package rpcnode.toolkit.nodes.infrastructure.host

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Persisted under `{nodeDir}/.toolkit/launch.json` so Sync Start can re-render the unit. */
@Serializable
data class HostNodeLaunchRecord(
    @SerialName("node_id") val nodeId: String = "",
    val network: String = "",
    val env: String = "",
    val kind: String = "",
    val entry: String = "",
    val args: List<String> = emptyList(),
    @SerialName("extract_archive_glob") val extractArchiveGlob: String? = null,
    @SerialName("normalize_dir") val normalizeDir: String? = null,
    /** Required JDK major for `java_jar` (e.g. 8 for java-tron). */
    @SerialName("java_major") val javaMajor: Int? = null,
    /** Process log relative to node_dir (e.g. logs/tron.log). */
    @SerialName("log_file") val logFile: String? = null,
)
