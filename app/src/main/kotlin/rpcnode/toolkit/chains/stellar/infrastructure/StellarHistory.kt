package rpcnode.toolkit.chains.stellar.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val optionsJson = Json { ignoreUnknownKeys = true }

/** Install option `history`: how many ledgers stellar-rpc keeps and whether it backfills them from the data lake. */
data class StellarHistoryPolicy(val mode: String, val retentionLedgers: Long, val backfillWanted: Boolean)

object StellarHistory
{
    const val GROUP = "history"
    const val WEEK = "week"
    const val MONTH = "month"
    const val FORWARD = "forward"

    /** uint32 max = never prune (a backfill makes no sense with it). */
    const val MAX_RETENTION = 4_294_967_295L

    fun parse(raw: String?): StellarHistoryPolicy =
        when (raw?.trim()?.lowercase())
        {
            MONTH -> StellarHistoryPolicy(MONTH, 518_400, backfillWanted = true)
            FORWARD -> StellarHistoryPolicy(FORWARD, MAX_RETENTION, backfillWanted = false)
            else -> StellarHistoryPolicy(WEEK, 120_960, backfillWanted = true)
        }

    fun fromJson(installOptionsJson: String?): StellarHistoryPolicy
    {
        val raw = installOptionsJson?.trim().orEmpty()
        if (raw.isEmpty())
        {
            return parse(null)
        }
        val root = runCatching { optionsJson.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return parse(null)
        return parse(root[GROUP]?.jsonPrimitive?.contentOrNull)
    }
}
