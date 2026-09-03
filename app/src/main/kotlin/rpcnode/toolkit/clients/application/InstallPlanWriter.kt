package rpcnode.toolkit.clients.application

import java.nio.file.Path
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreChainSpecs

data class InstallPlanFile(
    val role: String,
    val path: String,
    val arch: String? = null,
)

data class InstallPlanLaunch(
    val kind: String,
    val entry: String,
)

data class InstallPlan(
    val version: Int = 1,
    val network: String,
    val env: String,
    val program: String,
    val files: List<InstallPlanFile>,
    val launch: InstallPlanLaunch? = null,
)

/** Writes `install-plan.yml` so the host agent knows what to pull from /install/clients. */
fun interface InstallPlanWriter
{
    suspend fun write(dir: Path, plan: InstallPlan)
}

/** Always list VERSION in install-plan when the pin file is present on the panel clients dir. */
fun installPlanFilesIncludingVersion(dir: Path, files: List<InstallPlanFile>): List<InstallPlanFile>
{
    if (files.any { it.path.equals("VERSION", ignoreCase = true) })
    {
        return files
    }
    if (!java.nio.file.Files.isRegularFile(dir.resolve("VERSION")))
    {
        return files
    }
    return files + InstallPlanFile(role = "version", path = "VERSION")
}

fun appendVersionPlanFile(files: List<InstallPlanFile>): List<InstallPlanFile>
{
    if (files.any { it.path.equals("VERSION", ignoreCase = true) })
    {
        return files
    }
    return files + InstallPlanFile(role = "version", path = "VERSION")
}

fun inferArchFromFileName(name: String): String?
{
    val n = name.lowercase()
    return when
    {
        n.contains("aarch64") || n.contains("arm64") -> "aarch64"
        n.contains("x86_64") || n.contains("amd64") || n.contains("x64") -> "x86_64"
        else -> null
    }
}

fun inferLaunch(programId: String, files: List<InstallPlanFile>): InstallPlanLaunch?
{
    val jar = files.firstOrNull { it.path.endsWith(".jar", ignoreCase = true) }?.path
    if (jar != null)
    {
        return InstallPlanLaunch(kind = "java_jar", entry = jar)
    }
    val tar = files.firstOrNull { it.role == "artifact" }?.path
    BitcoreChainSpecs.byProgramId(programId)?.let { spec ->
        if (tar != null)
        {
            return InstallPlanLaunch(kind = "binary", entry = spec.daemonEntry)
        }
    }
    if (tar != null && programId.equals("zcash", ignoreCase = true))
    {
        return InstallPlanLaunch(kind = "binary", entry = "zcash/bin/zcashd")
    }
    if (tar != null && programId.equals("geth", ignoreCase = true))
    {
        return InstallPlanLaunch(kind = "binary", entry = "geth")
    }
    if (tar != null && programId.equals("sui-node", ignoreCase = true))
    {
        return InstallPlanLaunch(kind = "binary", entry = "bin/sui-node")
    }
    if (tar != null && programId.equals("hl-visor", ignoreCase = true))
    {
        return InstallPlanLaunch(kind = "binary", entry = "bin/hl-visor")
    }
    if (tar != null)
    {
        return InstallPlanLaunch(kind = "artifact", entry = tar)
    }
    return null
}

/**
 * Multi-program networks (e.g. ethereum geth + lighthouse) share one clients dir.
 * Keep every artifact already on disk so a later program download does not wipe siblings.
 */
fun mergeInstallPlanFiles(dir: Path, downloaded: List<InstallPlanFile>): List<InstallPlanFile>
{
    val byPath = linkedMapOf<String, InstallPlanFile>()
    for (f in downloaded)
    {
        byPath[f.path] = f
    }
    val onDisk = runCatching {
        java.nio.file.Files.list(dir).use { stream ->
            stream.iterator().asSequence()
                .filter { java.nio.file.Files.isRegularFile(it) }
                .map { it.fileName.toString() }
                .filter { name ->
                    name != "manifest.json" &&
                        name != "VERSION" &&
                        name != "install-plan.yml" &&
                        !name.startsWith(".")
                }
                .toList()
        }
    }.getOrDefault(emptyList())
    for (name in onDisk)
    {
        if (byPath.containsKey(name))
        {
            continue
        }
        val role = when
        {
            name.endsWith(".conf", ignoreCase = true) || name.endsWith(".ini", ignoreCase = true) -> "config"
            name.endsWith(".sh", ignoreCase = true) || name.endsWith(".tmpl", ignoreCase = true) -> "script"
            else -> "artifact"
        }
        byPath[name] = InstallPlanFile(role = role, path = name, arch = inferArchFromFileName(name))
    }
    return installPlanFilesIncludingVersion(dir, byPath.values.toList())
}

/** Prefer geth (EL primary) when multiple programs share the env dir. */
fun preferredInstallPlanProgram(fallback: String, files: List<InstallPlanFile>): String
{
    if (files.any { it.path.startsWith("geth-", ignoreCase = true) || it.path.equals("geth", ignoreCase = true) })
    {
        return "geth"
    }
    return fallback
}
