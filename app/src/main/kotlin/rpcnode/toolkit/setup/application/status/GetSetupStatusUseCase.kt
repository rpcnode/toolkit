package rpcnode.toolkit.setup.application.status

import rpcnode.toolkit.auth.domain.repository.CredentialStore

data class SetupStatus(
    val needed: Boolean,
)

class GetSetupStatusUseCase(
    private val credentials: CredentialStore,
)
{
    suspend operator fun invoke(): SetupStatus = SetupStatus(needed = !credentials.hasUsers())
}
