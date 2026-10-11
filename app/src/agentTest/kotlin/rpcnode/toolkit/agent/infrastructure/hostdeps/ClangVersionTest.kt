package rpcnode.toolkit.agent.infrastructure.hostdeps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClangVersionTest
{
    @Test
    fun reads_the_major_of_distro_builds()
    {
        assertEquals(21, ClangVersion.major("Ubuntu clang version 21.1.8 (++2025)\nTarget: aarch64-unknown-linux-gnu\n"))
        assertEquals(18, ClangVersion.major("Ubuntu clang version 18.1.3 (1ubuntu1)\n"))
        assertEquals(15, ClangVersion.major("Debian clang version 15.0.6\n"))
        assertEquals(17, ClangVersion.major("Apple clang version 17.0.0 (clang-1700.0.13.5)\n"))
    }

    @Test
    fun nothing_for_missing_or_garbage_output()
    {
        assertNull(ClangVersion.major(""))
        assertNull(ClangVersion.major("sh: clang: command not found"))
    }
}
