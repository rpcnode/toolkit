package rpcnode.toolkit.nodes.infrastructure.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostJavaBinaryTest
{
    @Test
    fun parses_legacy_1_8_and_modern_majors()
    {
        assertEquals(
            8,
            HostJavaBinary.parseMajorFromVersionOutput("""openjdk version "1.8.0_432""""),
        )
        assertEquals(
            25,
            HostJavaBinary.parseMajorFromVersionOutput("""openjdk version "25.0.4" 2026-07-21"""),
        )
        assertEquals(
            21,
            HostJavaBinary.parseMajorFromVersionOutput("""openjdk version "21.0.8" 2025-07-15"""),
        )
        assertNull(HostJavaBinary.parseMajorFromVersionOutput("not a version line"))
    }
}
