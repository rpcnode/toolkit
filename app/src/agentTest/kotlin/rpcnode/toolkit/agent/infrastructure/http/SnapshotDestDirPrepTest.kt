package rpcnode.toolkit.agent.infrastructure.http

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotDestDirPrepTest
{
    @Test
    fun creates_missing_dest_when_parent_writable()
    {
        val root = Files.createTempDirectory("snap-prep")
        val dest = root.resolve("a").resolve("b")
        val got = SnapshotDestDirPrep.ensureWritable(dest)
        assertTrue(Files.isDirectory(got))
        assertTrue(Files.isWritable(got))
    }

    @Test
    fun fails_with_permission_detail_when_parent_not_writable()
    {
        val root = Files.createTempDirectory("snap-prep-ro")
        val locked = root.resolve("locked")
        Files.createDirectories(locked)
        Files.setPosixFilePermissions(
            locked,
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
        )
        try
        {
            val dest = locked.resolve("child")
            val ex = assertFailsWith<IllegalStateException> {
                SnapshotDestDirPrep.ensureWritable(dest)
            }
            val msg = ex.message.orEmpty()
            assertTrue(msg.contains("cannot create dest_dir"), msg)
            assertTrue(msg.contains("no write permission"), msg)
            assertTrue(msg.contains(locked.toString()), msg)
            assertTrue(msg.contains("user="), msg)
        }
        finally
        {
            Files.setPosixFilePermissions(
                locked,
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }
    }

    @Test
    fun formatThrowable_never_bare_path()
    {
        val path = Files.createTempDirectory("snap-fmt")
        val msg = SnapshotDestDirPrep.formatThrowable(
            "snapshot dest_dir",
            path,
            java.nio.file.AccessDeniedException(path.toString()),
        )
        assertTrue(msg.contains("AccessDeniedException"), msg)
        assertTrue(msg.contains("user="), msg)
        assertTrue(msg.length > path.toString().length + 10, msg)
    }
}
