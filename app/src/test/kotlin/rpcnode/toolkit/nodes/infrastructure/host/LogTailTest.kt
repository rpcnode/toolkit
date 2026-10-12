package rpcnode.toolkit.nodes.infrastructure.host

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogTailTest
{
    @Test
    fun reads_only_the_end_of_a_big_file()
    {
        val f = Files.createTempFile("big", ".log")
        Files.write(f, ("x".repeat(3_000_000) + "\nLAST LINE\n").toByteArray())
        val tail = LogTail.read(f, 1024)!!
        assertEquals(1024, tail.length)
        assertTrue(tail.endsWith("LAST LINE\n"))
    }

    @Test
    fun small_and_missing_files()
    {
        val f = Files.createTempFile("small", ".log").also { Files.writeString(it, "abc") }
        assertEquals("abc", LogTail.read(f, 1024))
        assertNull(LogTail.read(f.resolveSibling("nope.log")))
    }
}
