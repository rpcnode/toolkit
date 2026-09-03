package rpcnode.toolkit.auth.infrastructure.persistence

import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import rpcnode.toolkit.auth.domain.model.Session
import rpcnode.toolkit.auth.domain.model.SessionToken
import rpcnode.toolkit.auth.domain.model.Username
import rpcnode.toolkit.auth.domain.repository.SessionStore
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteSessionStore(
    private val db: ToolkitDatabase,
    private val ttl: Duration = Session.TTL,
    private val random: SecureRandom = SecureRandom(),
) : SessionStore
{
    override fun create(username: Username): Session
    {
        val buf = ByteArray(32)
        random.nextBytes(buf)
        val token = SessionToken(buf.joinToString("") { "%02x".format(it) })
        val session = Session(token, username, Instant.now().plus(ttl))
        transaction(db.database) {
            SessionsTable.insert {
                it[SessionsTable.token] = session.token.value
                it[SessionsTable.username] = session.username.value
                it[SessionsTable.expiresAt] = session.expiresAt.toString()
            }
        }
        return session
    }

    override fun get(token: SessionToken): Username?
    {
        return transaction(db.database) {
            val row = SessionsTable.selectAll()
                .where { SessionsTable.token eq token.value }
                .firstOrNull()
                ?: return@transaction null
            val expiresAt = Instant.parse(row[SessionsTable.expiresAt])
            if (expiresAt.isBefore(Instant.now()))
            {
                SessionsTable.deleteWhere { SessionsTable.token eq token.value }
                return@transaction null
            }
            Username.parse(row[SessionsTable.username])
        }
    }

    override fun revoke(token: SessionToken)
    {
        transaction(db.database) {
            SessionsTable.deleteWhere { SessionsTable.token eq token.value }
        }
    }
}
