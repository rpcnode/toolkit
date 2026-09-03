package rpcnode.toolkit.agent.infrastructure.http

import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Creates [destDir] (and missing parents) and verifies it is writable.
 * Failures return operator-facing text — never a bare path alone.
 */
object SnapshotDestDirPrep
{
    fun ensureWritable(destDir: Path): Path
    {
        val dest = destDir.toAbsolutePath().normalize()
        val user = System.getProperty("user.name").orEmpty().ifBlank { "?" }
        if (Files.isDirectory(dest))
        {
            if (!Files.isWritable(dest))
            {
                error(
                    "dest_dir exists but is not writable: $dest " +
                        "(user=$user; fix ownership/permissions or remount)",
                )
            }
            return dest
        }
        if (Files.exists(dest) && !Files.isDirectory(dest))
        {
            error("dest_dir exists but is not a directory: $dest")
        }

        val missing = ArrayList<Path>()
        var cursor: Path? = dest
        while (cursor != null && !Files.exists(cursor))
        {
            missing.add(0, cursor)
            cursor = cursor.parent
        }
        val existing = cursor
            ?: error("cannot create dest_dir $dest — filesystem root missing")

        if (!Files.isDirectory(existing))
        {
            error(
                "cannot create dest_dir $dest — nearest existing path is not a directory: $existing",
            )
        }
        if (!Files.isWritable(existing))
        {
            val toCreate = missing.joinToString(" → ")
            error(
                "cannot create dest_dir $dest — no write permission on $existing " +
                    "(user=$user; missing: $toCreate). " +
                    "Mount the disk, chown/chmod that path, or run the agent as a user that can write there.",
            )
        }

        try
        {
            Files.createDirectories(dest)
        }
        catch (e: Exception)
        {
            error(formatCreateFailure(dest, existing, missing, user, e))
        }

        if (!Files.isDirectory(dest))
        {
            error("createDirectories returned but dest_dir is still missing: $dest (user=$user)")
        }
        if (!Files.isWritable(dest))
        {
            error(
                "created dest_dir but it is not writable: $dest " +
                    "(user=$user; check ACLs / mount options)",
            )
        }
        return dest
    }

    fun formatThrowable(action: String, path: Path, e: Throwable): String
    {
        val user = System.getProperty("user.name").orEmpty().ifBlank { "?" }
        val kind = e.javaClass.simpleName
        val detail = fileSystemDetail(e)
        return "$action $path failed: $kind: $detail (user=$user)"
    }

    private fun formatCreateFailure(
        dest: Path,
        existing: Path,
        missing: List<Path>,
        user: String,
        e: Exception,
    ): String
    {
        val kind = e.javaClass.simpleName
        val detail = fileSystemDetail(e)
        val toCreate = missing.joinToString(" → ")
        val hint = when (e)
        {
            is AccessDeniedException ->
                "Permission denied — chown/chmod $existing or run agent as a writable user."
            is FileSystemException ->
                "Check mount, disk space, and permissions under $existing."
            else ->
                "Check that the disk is mounted and the agent user can create directories."
        }
        return "cannot create dest_dir $dest: $kind: $detail " +
            "(user=$user; existing parent=$existing; missing: $toCreate). $hint"
    }

    private fun fileSystemDetail(e: Throwable): String
    {
        if (e is FileSystemException)
        {
            val reason = e.reason?.trim().orEmpty()
            val file = e.file?.trim().orEmpty()
            val other = e.otherFile?.trim().orEmpty()
            val parts = buildList {
                if (reason.isNotEmpty()) add(reason)
                if (file.isNotEmpty() && reason != file) add("file=$file")
                if (other.isNotEmpty()) add("other=$other")
            }
            if (parts.isNotEmpty())
            {
                return parts.joinToString("; ")
            }
        }
        val msg = e.message?.trim().orEmpty()
        return msg.ifBlank { "(no detail)" }
    }
}
