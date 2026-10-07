package rpcnode.toolkit.setup.application.check

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.settings.application.get.InstallFiles
import rpcnode.toolkit.settings.application.get.UrlProbe
import rpcnode.toolkit.settings.application.get.snapshotCdnReachable
import rpcnode.toolkit.settings.domain.repository.SettingsStore

data class SetupCheckItem(
    val id: String,
    val label: String,
    val ok: Boolean,
    val required: Boolean = false,
    val detail: String = "",
)

data class SetupCheckResult(
    val ready: Boolean,
    val checks: List<SetupCheckItem>,
)

class RunSetupCheckUseCase(
    private val store: SettingsStore,
    private val probe: UrlProbe,
    private val installFiles: InstallFiles,
    private val dbPath: Path,
)
{
    suspend operator fun invoke(): SetupCheckResult
    {
        val checks = listOf(
            serverCheck(),
            sqliteCheck(),
            binariesCheck(),
            cdnCheck(),
        )
        return SetupCheckResult(
            ready = checks.filter { it.required }.all { it.ok },
            checks = checks,
        )
    }

    private suspend fun serverCheck(): SetupCheckItem
    {
        val origin = store.installOrigin()?.value?.trimEnd('/')
        if (origin.isNullOrBlank())
        {
            return SetupCheckItem(
                id = "server",
                label = "server origin",
                ok = false,
                required = true,
                detail = "install_origin not set",
            )
        }
        val health = "$origin/healthz"
        val reachable = probe.reachable(health)
        return SetupCheckItem(
            id = "server",
            label = "server origin",
            ok = true,
            required = true,
            detail = if (reachable) origin else "$origin (saved; probe from server skipped/unreachable)",
        )
    }

    private fun sqliteCheck(): SetupCheckItem
    {
        val path = dbPath.toAbsolutePath()
        val ok = Files.isRegularFile(path) && Files.isReadable(path)
        return SetupCheckItem(
            id = "sqlite",
            label = "sqlite",
            ok = ok,
            required = true,
            detail = if (ok) path.toString() else "missing $path",
        )
    }

    private fun binariesCheck(): SetupCheckItem
    {
        val ok = installFiles.exists("binaries/rpcnode-agent.jar")
        return SetupCheckItem(
            id = "binaries",
            label = "agent jar",
            ok = ok,
            required = false,
            detail = if (ok) "rpcnode-agent.jar" else "optional — copy on first start",
        )
    }

    private suspend fun cdnCheck(): SetupCheckItem
    {
        val origin = store.snapshotCdnOrigin()?.value?.trimEnd('/')
        if (origin.isNullOrBlank())
        {
            return SetupCheckItem(
                id = "cdn",
                label = "snapshot cdn",
                ok = true,
                required = false,
                detail = "not set",
            )
        }
        val ok = probe.snapshotCdnReachable(origin)
        return SetupCheckItem(
            id = "cdn",
            label = "snapshot cdn",
            ok = ok,
            required = false,
            detail = if (ok) origin else "unreachable $origin",
        )
    }
}
