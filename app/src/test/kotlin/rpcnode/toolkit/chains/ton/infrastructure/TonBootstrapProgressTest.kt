package rpcnode.toolkit.chains.ton.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TonBootstrapProgressTest
{
    @Test
    fun blank_log_has_no_progress()
    {
        assertNull(TonBootstrapProgress.parse(""))
        assertNull(TonBootstrapProgress.parse("  \n"))
    }

    @Test
    fun early_stage_is_a_few_percent()
    {
        val pct = TonBootstrapProgress.parse("\u001b[1;32m[1/5]\u001b[0m Checking system requirements\n")
        assertEquals(4.0, pct)
    }

    @Test
    fun compile_counter_moves_the_bar()
    {
        val log = """
            [1/5] Checking system requirements
            TON does not exists, building
            [3/5] Source compilation, use 4 cpus
            [1200/4000] Building CXX object foo.o
        """.trimIndent()
        assertEquals(10.0 + 75.0 * 0.3, TonBootstrapProgress.parse(log)!!, 1e-9)
    }

    @Test
    fun stage_markers_are_not_build_steps()
    {
        val log = "[1/5] Checking system requirements\n[3/5] Source compilation, use 4 cpus\n"
        assertEquals(10.0, TonBootstrapProgress.parse(log))
    }

    @Test
    fun after_compile_waypoints_take_over()
    {
        val log = """
            [1/5] Checking system requirements
            [3999/4000] Linking CXX executable validator-engine
            [4000/4000] Linking CXX executable lite-client
            [4/5] Downloading config files
            [5/5] TON software installation complete
        """.trimIndent()
        assertEquals(88.0, TonBootstrapProgress.parse(log))
    }

    @Test
    fun only_the_last_retry_counts()
    {
        val log = """
            [1/5] Checking system requirements
            [3000/4000] Building CXX object a.o
            bootstrap failed rc=1
            [1/5] Checking system requirements
            Unsupported distribution/codename
        """.trimIndent()
        assertEquals(4.0, TonBootstrapProgress.parse(log))
    }

    @Test
    fun never_reports_done()
    {
        val log = "[1/5] Checking system requirements\nMytonctrl installation completed\ninstall.sh exit=0\n"
        val pct = assertNotNull(TonBootstrapProgress.parse(log))
        assertTrue(pct < 100.0)
    }
}
