package rpcnode.toolkit.agent.application.files

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WriteHostFileUseCaseTest
{
    @Test
    fun writes_absolute_path()
    {
        val dir = Files.createTempDirectory("agent-write")
        val path = dir.resolve("cfg.conf").toAbsolutePath().toString()
        val result = WriteHostFileUseCase()(path, "hello\n")
        assertIs<WriteHostFileResult.Ok>(result)
        assertEquals("hello\n", Files.readString(dir.resolve("cfg.conf")))
    }

    @Test
    fun rejects_relative_path()
    {
        val result = WriteHostFileUseCase()("tmp/x.conf", "x")
        assertIs<WriteHostFileResult.InvalidPath>(result)
    }

    @Test
    fun rejects_dotdot()
    {
        val result = WriteHostFileUseCase()("/tmp/../etc/passwd", "x")
        assertIs<WriteHostFileResult.InvalidPath>(result)
        assertTrue(true)
    }
}
