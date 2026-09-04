package rpcnode.toolkit.setup.application.create

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.auth.domain.model.Username
import rpcnode.toolkit.auth.domain.repository.CredentialStore
import rpcnode.toolkit.auth.infrastructure.persistence.MemorySessionStore

class CreateAdminUseCaseTest
{
    @Test
    fun first_run_creates_admin_and_session() = runTest {
        val credentials = FakeCredentials()
        val useCase = CreateAdminUseCase(credentials, MemorySessionStore())
        val result = useCase("", "secret-password")
        val created = assertIs<CreateAdminResult.Created>(result)
        assertEquals(Username.ADMIN, created.session.username)
        assertTrue(credentials.hasUsers())
    }

    @Test
    fun short_password_rejected() = runTest {
        val useCase = CreateAdminUseCase(FakeCredentials(), MemorySessionStore())
        assertIs<CreateAdminResult.PasswordTooShort>(useCase("admin", "short"))
    }

    @Test
    fun second_setup_updates_password() = runTest {
        val credentials = FakeCredentials()
        val useCase = CreateAdminUseCase(credentials, MemorySessionStore())
        useCase("admin", "secret-password")
        val second = assertIs<CreateAdminResult.Created>(useCase("admin", "new-secret-password"))
        assertTrue(second.updated)
        val user = Username.parseOrAdmin("admin")!!
        assertTrue(credentials.verify(user, "new-secret-password"))
    }

    @Test
    fun colon_username_rejected() = runTest {
        val useCase = CreateAdminUseCase(FakeCredentials(), MemorySessionStore())
        assertIs<CreateAdminResult.InvalidUsername>(useCase("a:b", "secret-password"))
    }
}

private class FakeCredentials : CredentialStore
{
    private val users = LinkedHashMap<Username, String>()

    override suspend fun hasUsers(): Boolean = users.isNotEmpty()

    override suspend fun create(username: Username, password: String)
    {
        users[username] = password
    }

    override suspend fun verify(username: Username, password: String): Boolean =
        users[username] == password
}
