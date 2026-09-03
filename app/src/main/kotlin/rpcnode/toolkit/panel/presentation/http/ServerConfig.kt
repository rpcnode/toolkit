package rpcnode.toolkit.panel.presentation.http

import java.nio.file.Files
import java.nio.file.Path

data class ServerConfig(
    val listen: String = System.getenv("PANEL_LISTEN") ?: "127.0.0.1",
    val port: Int = System.getenv("PANEL_PORT")?.toIntOrNull() ?: 8093,
    val dbPath: String = System.getenv("TOOLKIT_DB") ?: "database/toolkit.db",
    val htpasswdPath: String = System.getenv("PANEL_HTPASSWD") ?: "database/panel.htpasswd",
    val sessionsPath: String = System.getenv("PANEL_SESSIONS") ?: "database/panel-sessions.json",
    val corsOrigins: List<String> = corsOriginsFromEnv(),
    val panelVersion: String = System.getenv("PANEL_VERSION")?.trim().orEmpty(),
    val installDir: String = resolveInstallDir(System.getenv("PANEL_INSTALL_DIR") ?: "public/install"),
    /** Client tarballs land here: `<clientsDestDir>/<network>/<env>/`. Shared by Networks (files_ready) and Clients. */
    val clientsDestDir: String = System.getenv("CLIENT_SYNC_DEST") ?: "$installDir/clients",
    val installOriginOverride: String? = installOriginFromEnv(),
    val snapshotCdnOriginOverride: String? = System.getenv("SNAPSHOT_CDN_ORIGIN")?.trim()?.ifEmpty { null },
    val notifyKey: String? = System.getenv("RPCNODE_NOTIFY_KEY")?.trim()?.ifEmpty { null },
    /** IDEA run configs set `RPCNODE_DEV=1`: HTTP call log + DEBUG for our packages. */
    val dev: Boolean = rpcnodeDev(),
)

fun rpcnodeDev(raw: String? = System.getenv("RPCNODE_DEV")): Boolean
{
    val v = raw?.trim()?.lowercase() ?: return false
    return v == "1" || v == "true" || v == "yes"
}

/** `public/install` next to cwd, or `app/public/install` when IntelliJ cwd is the repo root. */
fun resolveInstallDir(configured: String): String
{
    val given = Path.of(configured)
    if (Files.isDirectory(given))
    {
        return given.toAbsolutePath().normalize().toString()
    }
    var dir = Path.of("").toAbsolutePath().normalize()
    repeat(8)
    {
        val hits = listOf(
            dir.resolve(configured),
            dir.resolve("public").resolve("install"),
            dir.resolve("app").resolve("public").resolve("install"),
        )
        for (hit in hits)
        {
            if (Files.isDirectory(hit))
            {
                return hit.normalize().toString()
            }
        }
        dir = dir.parent ?: return given.toAbsolutePath().normalize().toString()
    }
    return given.toAbsolutePath().normalize().toString()
}

internal fun installOriginFromEnv(): String?
{
    val base = System.getenv("INSTALL_BASE_URL")?.trim()?.ifEmpty { null }
    if (base != null)
    {
        return base
    }
    val agent = System.getenv("AGENT_DOWNLOAD_URL")?.trim()?.ifEmpty { null } ?: return null
    return agent.trimEnd('/')
        .removeSuffix("/agent.sh")
        .removeSuffix("/install/binaries/rpcnode-agent.jar")
        .removeSuffix("/install")
        .trimEnd('/')
}

internal fun corsOriginsFromEnv(): List<String>
{
    val raw = System.getenv("PANEL_CORS_ORIGINS")
    if (raw == null)
    {
        return listOf("http://127.0.0.1:5173", "http://localhost:5173")
    }
    if (raw.isBlank())
    {
        return emptyList()
    }
    return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
