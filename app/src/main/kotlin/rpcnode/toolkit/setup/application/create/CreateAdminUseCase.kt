package rpcnode.toolkit.setup.application.create

import rpcnode.toolkit.auth.domain.model.PanelPassword
import rpcnode.toolkit.auth.domain.model.Session
import rpcnode.toolkit.auth.domain.model.Username
import rpcnode.toolkit.auth.domain.repository.CredentialStore
import rpcnode.toolkit.auth.domain.repository.SessionStore

sealed interface CreateAdminResult
{
    data class Created(val session: Session, val updated: Boolean) : CreateAdminResult
    data object PasswordTooShort : CreateAdminResult
    data object InvalidUsername : CreateAdminResult
    data class WriteFailed(val reason: String) : CreateAdminResult
}

class CreateAdminUseCase(
    private val credentials: CredentialStore,
    private val sessions: SessionStore,
)
{
    suspend operator fun invoke(rawUsername: String, password: String): CreateAdminResult
    {
        if (!PanelPassword.isLongEnough(password))
        {
            return CreateAdminResult.PasswordTooShort
        }
        val username = Username.parseOrAdmin(rawUsername) ?: return CreateAdminResult.InvalidUsername
        return try
        {
            val updated = credentials.hasUsers()
            credentials.create(username, password)
            CreateAdminResult.Created(sessions.create(username), updated = updated)
        }
        catch (e: Exception)
        {
            if (e is kotlinx.coroutines.CancellationException) throw e
            CreateAdminResult.WriteFailed(e.message ?: "write_failed")
        }
    }
}
