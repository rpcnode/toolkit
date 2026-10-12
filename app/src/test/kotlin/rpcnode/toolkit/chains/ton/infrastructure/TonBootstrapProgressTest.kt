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
        assertEquals(6.0 + 34.0 * 0.3, TonBootstrapProgress.parse(log)!!, 1e-9)
    }

    @Test
    fun stage_markers_are_not_build_steps()
    {
        val log = "[1/5] Checking system requirements\n[3/5] Source compilation, use 4 cpus\n"
        assertEquals(6.0, TonBootstrapProgress.parse(log))
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
        assertEquals(41.0, TonBootstrapProgress.parse(log))
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

    @Test
    fun dump_download_checksum_and_extraction_move_the_bar()
    {
        val head = """
            [1/5] Checking system requirements
            [4/5] Running mytoninstaller
            [debug] 11.10.2026, 13:02:18.947 (UTC) <MainThread> start download_dump function
        """.trimIndent() + "\n"
        val downloading = head + "[#dc25ec 68GiB/252GiB(27%) CN:8 DL:275MiB ETA:11m24s]\n"
        assertEquals(45.0 + 35.0 * 0.27, TonBootstrapProgress.parse(downloading)!!, 1e-9)

        val checksum = head + "[#034e29 420GiB/420GiB(100%) CN:0] [Checksum:#034e29 210GiB/420GiB(50%)]\n"
        assertEquals(82.5, TonBootstrapProgress.parse(checksum)!!, 1e-9)

        val extracting = checksum + "Dump downloaded to /var/ton-work/dump-cache/x.tar.lz in 1h 15m 31s. Starting extraction to /var/ton-work/db/\n"
        assertEquals(86.0, TonBootstrapProgress.parse(extracting))

        val extracted = extracting + "Dump extracted to /var/ton-work/db/ in 39m 6s\n"
        assertEquals(95.0, TonBootstrapProgress.parse(extracted))
    }

    @Test
    fun aria_lines_before_the_dump_step_do_not_count()
    {
        val log = "[1/5] Checking system requirements\n[#aaaaaa 1GiB/2GiB(50%) CN:1]\n"
        assertEquals(4.0, TonBootstrapProgress.parse(log))
    }

    private val extracting = """
        Checking system requirements
        start download_dump
        dumpDiskSize: 1000
        Starting extraction to /var/ton-work/db/
    """.trimIndent()

    @Test
    fun the_silent_extraction_is_read_from_the_unpacked_size_on_disk()
    {
        // 86 (start of the extraction) .. 94 (just before "Dump extracted")
        assertEquals(86.0, TonBootstrapProgress.parse(extracting) { 0L })
        assertEquals(90.0, TonBootstrapProgress.parse(extracting) { 500L })
        assertEquals(94.0, TonBootstrapProgress.parse(extracting) { 5_000L }, "more than announced is capped")
    }

    @Test
    fun without_a_size_or_a_readable_disk_the_bar_waits_at_the_start_of_the_extraction()
    {
        assertEquals(86.0, TonBootstrapProgress.parse(extracting))
        assertEquals(86.0, TonBootstrapProgress.parse(extracting) { null })
        assertEquals(86.0, TonBootstrapProgress.parse("Checking system requirements\nStarting extraction") { 500L })
    }

    @Test
    fun the_disk_is_not_asked_outside_the_extraction()
    {
        var asked = 0
        TonBootstrapProgress.parse("Checking system requirements\nstart download_dump") { asked++; 1L }
        TonBootstrapProgress.parse("$extracting\nDump extracted") { asked++; 1L }
        assertEquals(0, asked)
        assertEquals(95.0, TonBootstrapProgress.parse("$extracting\nDump extracted") { 500L })
    }
}
