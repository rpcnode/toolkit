package rpcnode.toolkit.auth.infrastructure.persistence

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import rpcnode.toolkit.auth.domain.model.Session
import rpcnode.toolkit.auth.domain.model.SessionToken
import rpcnode.toolkit.auth.domain.model.Username
import rpcnode.toolkit.auth.domain.repository.SessionStore

class MemorySessionStore(
    private val ttl: Duration = Session.TTL,
    private val random: SecureRandom = SecureRandom(),
) : SessionStore
{
    private val sessions = LinkedHashMap<String, Session>()

    override fun create(username: Username): Session
    {
        val buf = ByteArray(32)
        random.nextBytes(buf)
        val token = SessionToken(buf.joinToString("") { "%02x".format(it) })
        val session = Session(token, username, Instant.now().plus(ttl))
        sessions[token.value] = session
        return session
    }

    override fun get(token: SessionToken): Username?
    {
        val session = sessions[token.value] ?: return null
        if (session.expiresAt.isBefore(Instant.now()))
        {
            sessions.remove(token.value)
            return null
        }
        return session.username
    }

    override fun revoke(token: SessionToken)
    {
        sessions.remove(token.value)
    }
}
