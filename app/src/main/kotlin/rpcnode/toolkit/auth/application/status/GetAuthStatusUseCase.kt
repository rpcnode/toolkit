package rpcnode.toolkit.auth.application.status

import rpcnode.toolkit.auth.domain.model.SessionToken
import rpcnode.toolkit.auth.domain.model.Username
import rpcnode.toolkit.auth.domain.repository.SessionStore

data class AuthStatus(
    val authenticated: Boolean,
    val user: Username?,
)

class GetAuthStatusUseCase(
    private val sessions: SessionStore,
)
{
    operator fun invoke(token: SessionToken?): AuthStatus
    {
        val user = token?.let { sessions.get(it) }
        return AuthStatus(
            authenticated = user != null,
            user = user,
        )
    }
}
