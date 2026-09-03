package rpcnode.toolkit.settings.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.settings.application.get.InstallFiles

class DiskInstallFiles(private val root: Path) : InstallFiles
{
    override fun exists(relative: String): Boolean
    {
        if (relative.isBlank() || relative.contains(".."))
        {
            return false
        }
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root.normalize()))
        {
            return false
        }
        return Files.isRegularFile(resolved)
    }
}
