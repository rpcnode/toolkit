package rpcnode.toolkit.auth.infrastructure.persistence

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.auth.domain.model.SessionToken
import rpcnode.toolkit.auth.domain.model.Username
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteSessionStoreTest
{
    @Test
    fun session_survives_reopen()
    {
        val path = Files.createTempDirectory("sessions").resolve("toolkit.db")
        val created = SqliteSessionStore(ToolkitDatabase(path)).create(Username.ADMIN)
        val reopened = SqliteSessionStore(ToolkitDatabase(path))
        assertEquals(Username.ADMIN, reopened.get(created.token))
    }

    @Test
    fun expired_session_is_forgotten()
    {
        val path = Files.createTempDirectory("sessions-exp").resolve("toolkit.db")
        val store = SqliteSessionStore(ToolkitDatabase(path), ttl = Duration.ofMillis(1))
        val created = store.create(Username.ADMIN)
        Thread.sleep(5)
        assertNull(store.get(created.token))
        assertNull(store.get(SessionToken(created.token.value)))
    }

    @Test
    fun revoke_drops_the_token()
    {
        val path = Files.createTempDirectory("sessions-rev").resolve("toolkit.db")
        val store = SqliteSessionStore(ToolkitDatabase(path))
        val created = store.create(Username.ADMIN)
        store.revoke(created.token)
        assertNull(store.get(created.token))
    }

    @Test
    fun ttl_is_24_hours()
    {
        val path = Files.createTempDirectory("sessions-ttl").resolve("toolkit.db")
        val created = SqliteSessionStore(ToolkitDatabase(path)).create(Username.ADMIN)
        val remaining = Duration.between(Instant.now(), created.expiresAt)
        assertTrue(remaining.toHours() in 23..24)
    }
}
