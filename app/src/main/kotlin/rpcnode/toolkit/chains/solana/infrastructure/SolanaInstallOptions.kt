package rpcnode.toolkit.chains.solana.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Reads install_options.node (full|archive). */
fun solanaNodeMode(installOptionsJson: String?): String
{
    val raw = installOptionsJson?.trim().orEmpty()
    if (raw.isEmpty())
    {
        return SolanaClusters.normalizeMode(null)
    }
    val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return SolanaClusters.normalizeMode(null)
    val node = root["node"]?.jsonPrimitive?.contentOrNull
    return SolanaClusters.normalizeMode(node)
}

/** Agave RPC capacity knobs from install_options (Start Client config). */
data class SolanaRpcTuning(
    val rpcThreads: Int = SolanaUnitBodies.RPC_THREADS,
    val rpcPubsubWorkerThreads: Int = SolanaUnitBodies.RPC_PUBSUB_WORKER_THREADS,
    val rpcPubsubMaxActiveSubscriptions: Int = SolanaUnitBodies.RPC_PUBSUB_MAX_ACTIVE_SUBSCRIPTIONS,
    val rpcMaxRequestBodySize: Int = SolanaUnitBodies.RPC_MAX_REQUEST_BODY_SIZE,
    val limitNofile: Int = SolanaUnitBodies.NODE_NOFILE,
)

fun solanaRpcTuning(installOptionsJson: String?): SolanaRpcTuning
{
    val raw = installOptionsJson?.trim().orEmpty()
    if (raw.isEmpty())
    {
        return SolanaRpcTuning()
    }
    val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return SolanaRpcTuning()
    fun intOpt(key: String, default: Int): Int
    {
        val v = root[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return v.toIntOrNull()?.takeIf { it > 0 } ?: default
    }
    return SolanaRpcTuning(
        rpcThreads = intOpt("rpc_threads", SolanaUnitBodies.RPC_THREADS),
        rpcPubsubWorkerThreads = intOpt("rpc_pubsub_worker_threads", SolanaUnitBodies.RPC_PUBSUB_WORKER_THREADS),
        rpcPubsubMaxActiveSubscriptions = intOpt(
            "rpc_pubsub_max_active_subscriptions",
            SolanaUnitBodies.RPC_PUBSUB_MAX_ACTIVE_SUBSCRIPTIONS,
        ),
        rpcMaxRequestBodySize = intOpt("rpc_max_request_body_size", SolanaUnitBodies.RPC_MAX_REQUEST_BODY_SIZE),
        limitNofile = intOpt("LimitNOFILE", SolanaUnitBodies.NODE_NOFILE),
    )
}

/**
 * Agave / Anza system-tuning knobs → `/etc/sysctl.d/21-solana.conf` on Start.
 * Defaults match toolkit-go ensureSysctl and docs.anza.xyz system-tuning.
 */
data class SolanaSysctlTuning(
    val rmemDefault: Long = RECOMMENDED_RMEM,
    val rmemMax: Long = RECOMMENDED_RMEM,
    val wmemDefault: Long = RECOMMENDED_WMEM,
    val wmemMax: Long = RECOMMENDED_WMEM,
    val vmMaxMapCount: Long = RECOMMENDED_VM_MAX_MAP_COUNT,
    val fsNrOpen: Long = RECOMMENDED_FS_NR_OPEN,
)
{
    companion object
    {
        const val RECOMMENDED_RMEM = 134_217_728L
        const val RECOMMENDED_WMEM = 134_217_728L
        const val RECOMMENDED_VM_MAX_MAP_COUNT = 1_000_000L
        const val RECOMMENDED_FS_NR_OPEN = 8_388_608L

        val recommended: SolanaSysctlTuning = SolanaSysctlTuning()

        val KEYS: List<String> = listOf(
            "net.core.rmem_default",
            "net.core.rmem_max",
            "net.core.wmem_default",
            "net.core.wmem_max",
            "vm.max_map_count",
            "fs.nr_open",
        )

        /** sysctl key → Start install_options key. */
        val OPTION_BY_KEY: Map<String, String> = mapOf(
            "net.core.rmem_default" to "sysctl_rmem_default",
            "net.core.rmem_max" to "sysctl_rmem_max",
            "net.core.wmem_default" to "sysctl_wmem_default",
            "net.core.wmem_max" to "sysctl_wmem_max",
            "vm.max_map_count" to "sysctl_vm_max_map_count",
            "fs.nr_open" to "sysctl_fs_nr_open",
        )
    }

    fun asMap(): Map<String, Long> = mapOf(
        "net.core.rmem_default" to rmemDefault.coerceAtLeast(65_536L),
        "net.core.rmem_max" to rmemMax.coerceAtLeast(65_536L),
        "net.core.wmem_default" to wmemDefault.coerceAtLeast(65_536L),
        "net.core.wmem_max" to wmemMax.coerceAtLeast(65_536L),
        "vm.max_map_count" to vmMaxMapCount.coerceAtLeast(65_536L),
        "fs.nr_open" to fsNrOpen.coerceAtLeast(1_048_576L),
    )

    fun confBody(): String =
        asMap().entries.joinToString("\n", postfix = "\n") { (k, v) -> "$k = $v" }
}

fun solanaSysctlTuning(installOptionsJson: String?): SolanaSysctlTuning
{
    val raw = installOptionsJson?.trim().orEmpty()
    if (raw.isEmpty())
    {
        return SolanaSysctlTuning.recommended
    }
    val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull()
        ?: return SolanaSysctlTuning.recommended
    fun longOpt(key: String, default: Long): Long
    {
        val v = root[key]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return v.toLongOrNull()?.takeIf { it > 0 } ?: default
    }
    return SolanaSysctlTuning(
        rmemDefault = longOpt("sysctl_rmem_default", SolanaSysctlTuning.RECOMMENDED_RMEM),
        rmemMax = longOpt("sysctl_rmem_max", SolanaSysctlTuning.RECOMMENDED_RMEM),
        wmemDefault = longOpt("sysctl_wmem_default", SolanaSysctlTuning.RECOMMENDED_WMEM),
        wmemMax = longOpt("sysctl_wmem_max", SolanaSysctlTuning.RECOMMENDED_WMEM),
        vmMaxMapCount = longOpt("sysctl_vm_max_map_count", SolanaSysctlTuning.RECOMMENDED_VM_MAX_MAP_COUNT),
        fsNrOpen = longOpt("sysctl_fs_nr_open", SolanaSysctlTuning.RECOMMENDED_FS_NR_OPEN),
    )
}
