package rpcnode.toolkit.shared.infrastructure.log

import org.slf4j.LoggerFactory

/** One logger for inbound Ktor calls and outbound HttpClient calls. */
object HttpIoLog
{
    private val log = LoggerFactory.getLogger("rpcnode.http")

    fun inbound(method: String, path: String, status: Int, elapsedMs: Long, from: String): String =
        "in $method $path → $status ${elapsedMs}ms from $from"

    fun outbound(method: String, url: String, status: Int, elapsedMs: Long, error: String? = null)
    {
        val err = if (error.isNullOrBlank()) "" else " $error"
        log.info("out $method $url → $status ${elapsedMs}ms$err")
    }
}
