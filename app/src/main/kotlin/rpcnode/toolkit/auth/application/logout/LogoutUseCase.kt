package rpcnode.toolkit.auth.application.logout

import rpcnode.toolkit.auth.domain.model.SessionToken
import rpcnode.toolkit.auth.domain.repository.SessionStore

class LogoutUseCase(
    private val sessions: SessionStore,
)
{
    operator fun invoke(token: SessionToken?)
    {
        if (token != null)
        {
            sessions.revoke(token)
        }
    }
}
