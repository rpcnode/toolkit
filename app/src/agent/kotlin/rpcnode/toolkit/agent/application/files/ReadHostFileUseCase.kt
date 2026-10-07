package rpcnode.toolkit.agent.application.files

import java.nio.file.Files
import java.nio.file.Path

sealed interface ReadHostFileResult
{
    /** [exists] false = the file is not there yet (not an error: the panel offers to create it). */
    data class Ok(val path: String, val exists: Boolean, val content: String) : ReadHostFileResult
    data object InvalidPath : ReadHostFileResult
    data object TooLarge : ReadHostFileResult
    data class Failed(val detail: String) : ReadHostFileResult
}

/** Reads a text file at an absolute path on the host (node config editor). */
class ReadHostFileUseCase(private val maxBytes: Long = MAX_BYTES)
{
    operator fun invoke(pathRaw: String): ReadHostFileResult
    {
        val path = pathRaw.trim()
        if (path.isEmpty() || !path.startsWith("/") || path.contains(".."))
        {
            return ReadHostFileResult.InvalidPath
        }
        return try
        {
            val file = Path.of(path)
            if (!Files.exists(file))
            {
                return ReadHostFileResult.Ok(path, exists = false, content = "")
            }
            if (!Files.isRegularFile(file))
            {
                return ReadHostFileResult.InvalidPath
            }
            if (Files.size(file) > maxBytes)
            {
                return ReadHostFileResult.TooLarge
            }
            ReadHostFileResult.Ok(path, exists = true, content = Files.readString(file))
        }
        catch (e: Exception)
        {
            ReadHostFileResult.Failed(e.message?.ifBlank { null } ?: e.javaClass.simpleName)
        }
    }

    companion object
    {
        const val MAX_BYTES = 2L * 1024 * 1024
    }
}
