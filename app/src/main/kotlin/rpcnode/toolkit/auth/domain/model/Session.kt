package rpcnode.toolkit.auth.domain.model

import java.time.Duration
import java.time.Instant

@JvmInline
value class SessionToken(val value: String)

data class Session(
    val token: SessionToken,
    val username: Username,
    val expiresAt: Instant,
)
{
    companion object
    {
        val TTL: Duration = Duration.ofHours(24)
    }
}
