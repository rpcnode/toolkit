package rpcnode.toolkit.install.application

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServeInstallFileUseCaseTest
{
    @Test
    fun serves_a_regular_file_under_root()
    {
        val root = Files.createTempDirectory("install")
        Files.writeString(root.resolve("note.txt"), "#!/bin/sh\n")
        val got = ServeInstallFileUseCase(root)("note.txt")
        assertEquals("note.txt", got!!.fileName.toString())
    }

    @Test
    fun rejects_parent_segments()
    {
        val root = Files.createTempDirectory("install")
        val serve = ServeInstallFileUseCase(root)
        assertNull(serve("../note.txt"))
        assertNull(serve("binaries/../../secret"))
        assertNull(serve(""))
    }
}
