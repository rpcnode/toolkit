package rpcnode.toolkit.agent.application.update

import java.util.concurrent.atomic.AtomicBoolean
import rpcnode.toolkit.agent.application.enroll.PanelEnrollmentStore

sealed interface UpdateAgentResult
{
    data class UpToDate(val version: String, val remoteVersion: String) : UpdateAgentResult
    data class Updated(
        val version: String,
        val localVersion: String,
        val steps: List<String>,
    ) : UpdateAgentResult
    data object ChannelUnavailable : UpdateAgentResult
    data class Failed(val message: String, val localVersion: String, val remoteVersion: String = "") : UpdateAgentResult
    data object InProgress : UpdateAgentResult
}

class UpdateAgentUseCase(
    private val localVersion: String,
    private val resolvePanelUrl: suspend () -> String?,
    private val channel: AgentReleaseChannel,
    private val installer: AgentJarInstaller,
    private val restarter: AgentRestarter,
    private val busy: AtomicBoolean = AtomicBoolean(false),
)
{
    constructor(
        localVersion: String,
        enrollment: PanelEnrollmentStore,
        channel: AgentReleaseChannel,
        installer: AgentJarInstaller,
        restarter: AgentRestarter,
    ) : this(
        localVersion = localVersion,
        resolvePanelUrl = { enrollment.read()?.panelUrl?.ifBlank { null } },
        channel = channel,
        installer = installer,
        restarter = restarter,
    )

    suspend operator fun invoke(force: Boolean = false): UpdateAgentResult
    {
        if (!busy.compareAndSet(false, true))
        {
            return UpdateAgentResult.InProgress
        }
        try
        {
            val panelUrl = resolvePanelUrl()?.trim()?.trimEnd('/')?.ifEmpty { null }
                ?: return UpdateAgentResult.ChannelUnavailable
            val remote = channel.version(panelUrl)?.trim().orEmpty()
            if (remote.isEmpty())
            {
                return UpdateAgentResult.ChannelUnavailable
            }
            val local = localVersion.trim()
            if (remote == local && !force)
            {
                return UpdateAgentResult.UpToDate(version = local, remoteVersion = remote)
            }
            return when (val installed = installer.install(panelUrl))
            {
                is AgentInstallResult.Failed ->
                    UpdateAgentResult.Failed(
                        message = installed.message,
                        localVersion = local,
                        remoteVersion = remote,
                    )
                is AgentInstallResult.Ok ->
                {
                    restarter.schedule()
                    UpdateAgentResult.Updated(
                        version = remote,
                        localVersion = local,
                        steps = listOf("jar ${installed.jar}", "restart scheduled"),
                    )
                }
            }
        }
        finally
        {
            busy.set(false)
        }
    }
}
