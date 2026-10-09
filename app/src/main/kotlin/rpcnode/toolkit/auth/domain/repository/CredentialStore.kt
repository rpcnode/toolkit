package rpcnode.toolkit.auth.domain.repository

import rpcnode.toolkit.auth.domain.model.Username

interface CredentialStore
{
    suspend fun hasUsers(): Boolean
    suspend fun create(username: Username, password: String)
    suspend fun verify(username: Username, password: String): Boolean
}
