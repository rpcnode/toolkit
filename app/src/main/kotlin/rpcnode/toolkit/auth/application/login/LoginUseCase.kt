package rpcnode.toolkit.auth.application.login

import rpcnode.toolkit.auth.domain.model.Session
import rpcnode.toolkit.auth.domain.model.Username
import rpcnode.toolkit.auth.domain.repository.CredentialStore
import rpcnode.toolkit.auth.domain.repository.SessionStore

sealed interface LoginResult
{
    data class Ok(val session: Session) : LoginResult
    data object NeedsSetup : LoginResult
    data object MissingFields : LoginResult
    data object InvalidCredentials : LoginResult
}

class LoginUseCase(
    private val credentials: CredentialStore,
    private val sessions: SessionStore,
)
{
    suspend operator fun invoke(rawUsername: String, password: String): LoginResult
    {
        if (!credentials.hasUsers())
        {
            return LoginResult.NeedsSetup
        }
        val username = Username.parse(rawUsername)
        if (username == null || password.isEmpty())
        {
            return LoginResult.MissingFields
        }
        if (!credentials.verify(username, password))
        {
            return LoginResult.InvalidCredentials
        }
        return LoginResult.Ok(sessions.create(username))
    }
}
