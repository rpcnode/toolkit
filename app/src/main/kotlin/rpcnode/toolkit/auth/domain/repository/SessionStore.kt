package rpcnode.toolkit.auth.domain.repository

import rpcnode.toolkit.auth.domain.model.Session
import rpcnode.toolkit.auth.domain.model.SessionToken
import rpcnode.toolkit.auth.domain.model.Username

interface SessionStore
{
    fun create(username: Username): Session
    fun get(token: SessionToken): Username?
    fun revoke(token: SessionToken)
}
