package rpcnode.toolkit.setup.application.stage

import rpcnode.toolkit.settings.domain.repository.SettingsStore

sealed interface SetSetupStageResult
{
    data class Ok(val stage: String) : SetSetupStageResult
    data object Invalid : SetSetupStageResult
}

class SetSetupStageUseCase(
    private val store: SettingsStore,
)
{
    suspend operator fun invoke(raw: String): SetSetupStageResult
    {
        val stage = raw.trim().lowercase()
        if (stage !in ALLOWED)
        {
            return SetSetupStageResult.Invalid
        }
        store.setSetupStage(stage)
        return SetSetupStageResult.Ok(stage)
    }

    private companion object
    {
        val ALLOWED = setOf("admin", "server", "networks", "done")
    }
}
