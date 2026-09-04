package rpcnode.toolkit.setup.application.finish

import java.time.Instant
import rpcnode.toolkit.settings.application.get.InstallStamp
import rpcnode.toolkit.settings.application.get.InstallStampReader
import rpcnode.toolkit.settings.application.get.InstallStampWriter
import rpcnode.toolkit.settings.domain.repository.SettingsStore

sealed interface FinishSetupResult
{
    data object Ok : FinishSetupResult
    data class WriteFailed(val reason: String) : FinishSetupResult
}

class FinishSetupUseCase(
    private val store: SettingsStore,
    private val reader: InstallStampReader,
    private val writer: InstallStampWriter,
    private val panelVersion: String,
)
{
    suspend operator fun invoke(): FinishSetupResult
    {
        return try
        {
            val now = Instant.now().toString()
            val existing = reader.read()
            val version = panelVersion.trim().ifEmpty {
                existing?.version?.ifBlank { null } ?: "0.0.0"
            }
            writer.write(
                InstallStamp(
                    version = version,
                    installedAt = existing?.installedAt?.ifBlank { null } ?: now,
                    updatedAt = now,
                ),
            )
            store.setSetupStage("done")
            FinishSetupResult.Ok
        }
        catch (e: Exception)
        {
            if (e is kotlinx.coroutines.CancellationException) throw e
            FinishSetupResult.WriteFailed(e.message ?: "write_failed")
        }
    }
}
