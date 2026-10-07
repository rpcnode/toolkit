package rpcnode.toolkit.agent.application.files

import java.nio.file.Files
import java.nio.file.Path

sealed interface WriteHostFileResult
{
    data class Ok(val path: String) : WriteHostFileResult
    data object InvalidPath : WriteHostFileResult
    data class Failed(val detail: String) : WriteHostFileResult
}

/**
 * Writes a rendered client config (or other text) to an absolute path on the host.
 * Creates parent directories. Rejects empty / relative / `..` paths.
 */
class WriteHostFileUseCase
{
    operator fun invoke(pathRaw: String, content: String): WriteHostFileResult
    {
        val path = pathRaw.trim()
        if (path.isEmpty() || !path.startsWith("/") || path.contains(".."))
        {
            return WriteHostFileResult.InvalidPath
        }
        return try
        {
            val file = Path.of(path)
            val parent = file.parent
            if (parent != null)
            {
                Files.createDirectories(parent)
            }
            Files.writeString(file, content)
            WriteHostFileResult.Ok(path)
        }
        catch (e: Exception)
        {
            WriteHostFileResult.Failed(e.message?.ifBlank { null } ?: e.javaClass.simpleName)
        }
    }
}
