package rpcnode.toolkit.install.application

import java.nio.file.Files
import java.nio.file.Path

/** File under `public/install` for `GET /install/{path}`. Rejects `..`. */
class ServeInstallFileUseCase(private val root: Path)
{
    operator fun invoke(relative: String): Path?
    {
        val rel = relative.trim().trimStart('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." || it.isEmpty() })
        {
            return null
        }
        val base = root.toAbsolutePath().normalize()
        val resolved = base.resolve(rel).normalize()
        if (!resolved.startsWith(base))
        {
            return null
        }
        return resolved.takeIf { Files.isRegularFile(it) }
    }
}
