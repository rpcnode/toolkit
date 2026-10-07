package rpcnode.toolkit.settings.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.settings.domain.model.GitHubToken
import rpcnode.toolkit.settings.domain.model.InstallOrigin
import rpcnode.toolkit.settings.domain.model.SnapshotCdnOrigin
import rpcnode.toolkit.settings.domain.model.StoredGitHubToken
import rpcnode.toolkit.settings.domain.repository.SettingsStore
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteSettingsStore(
    private val db: ToolkitDatabase,
    private val githubTokenFile: Path,
    private val secrets: AesGcmSecretBox,
) : SettingsStore
{
    private val lock = Any()

    override suspend fun installOrigin(): InstallOrigin? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val raw = db.getSetting(KEY_ORIGIN).orEmpty()
            when (val parsed = InstallOrigin.parse(raw))
            {
                is InstallOrigin.Parse.Ok -> parsed.origin
                else -> null
            }
        }
    }

    override suspend fun setInstallOrigin(origin: InstallOrigin) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_ORIGIN, origin.value)
        }
    }

    override suspend fun snapshotCdnOrigin(): SnapshotCdnOrigin? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val raw = db.getSetting(KEY_SNAPSHOT_CDN).orEmpty()
            when (val parsed = SnapshotCdnOrigin.parse(raw))
            {
                is SnapshotCdnOrigin.Parse.Ok -> parsed.origin
                else -> null
            }
        }
    }

    override suspend fun setSnapshotCdnOrigin(origin: SnapshotCdnOrigin) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_SNAPSHOT_CDN, origin.value)
        }
    }

    override suspend fun clearSnapshotCdnOrigin() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_SNAPSHOT_CDN, "")
        }
    }

    override suspend fun githubToken(): StoredGitHubToken = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val enc = db.getSetting(KEY_GITHUB_ENC).orEmpty().trim()
            if (enc.isEmpty())
            {
                return@synchronized StoredGitHubToken.Absent
            }
            val plain = try
            {
                secrets.decrypt(enc)
            }
            catch (_: Exception)
            {
                return@synchronized StoredGitHubToken.Corrupt
            }
            val token = GitHubToken.parse(plain)
            if (token == null)
            {
                StoredGitHubToken.Corrupt
            }
            else
            {
                StoredGitHubToken.Present(token)
            }
        }
    }

    override suspend fun setGithubToken(token: GitHubToken) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_GITHUB_ENC, secrets.encrypt(token.value))
            writeTokenFile(token.value)
        }
    }

    override suspend fun clearGithubToken() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_GITHUB_ENC, "")
            writeTokenFile("")
        }
    }

    override suspend fun setupStage(): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.getSetting(KEY_SETUP_STAGE)?.trim()?.ifEmpty { null }
        }
    }

    override suspend fun setSetupStage(stage: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            db.setSetting(KEY_SETUP_STAGE, stage.trim())
        }
    }

    private fun writeTokenFile(token: String)
    {
        val parent = githubTokenFile.parent
        if (parent != null)
        {
            Files.createDirectories(parent)
        }
        if (token.isEmpty())
        {
            Files.deleteIfExists(githubTokenFile)
            return
        }
        Files.writeString(
            githubTokenFile,
            token + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
        )
        runCatching {
            Files.setPosixFilePermissions(
                githubTokenFile,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
    }

    private companion object
    {
        const val KEY_ORIGIN = "install_origin"
        const val KEY_SNAPSHOT_CDN = "snapshot_cdn_origin"
        const val KEY_GITHUB_ENC = "github_token_enc"
        const val KEY_SETUP_STAGE = "setup_stage"
    }
}
