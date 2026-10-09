package rpcnode.toolkit.agent.application.client

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnitStabilityCheckTest
{
    private fun check(vararg samples: UnitSample, trial: Int = samples.size - 1): String?
    {
        var i = 0
        // each probe call walks the scripted timeline; the last sample repeats
        val probe = { _: String -> samples[minOf(i++, samples.lastIndex)] }
        return UnitStabilityCheck(probe, trialSeconds = trial, sleepSeconds = { }).verify("rpcnode-x.service")
    }

    private fun running(pid: Long = 100, restarts: Long = 0) = UnitSample("active", "running", pid, restarts)

    @Test
    fun a_unit_that_keeps_one_process_is_stable()
    {
        assertNull(check(UnitSample("activating", "start"), running(), running(), running(), running()))
    }

    @Test
    fun a_unit_that_never_comes_up_fails()
    {
        val why = check(UnitSample("activating", "start"), UnitSample("activating", "start"), UnitSample("inactive", "dead"))
        assertNotNull(why)
        assertTrue(why.contains("did not stay active"), why)
    }

    @Test
    fun a_crash_loop_is_detected_even_if_it_is_active_again_at_the_end()
    {
        // geth panics ~12 s after start and systemd restarts it: active → auto-restart → active (new pid)
        val why = check(
            running(pid = 100),
            running(pid = 100),
            UnitSample("activating", "auto-restart", 0, 1),
            running(pid = 200, restarts = 1),
            running(pid = 200, restarts = 1),
        )
        assertNotNull(why)
        assertTrue(why.contains("crash loop"), why)
    }

    @Test
    fun a_new_pid_with_a_higher_restart_counter_is_a_restart()
    {
        val why = check(running(100, 0), running(100, 0), running(200, 1), running(200, 1))
        assertNotNull(why)
        assertTrue(why.contains("100 → 200"), why)
    }

    @Test
    fun failing_after_it_started_is_reported()
    {
        val why = check(running(), running(), UnitSample("failed", "failed"), UnitSample("failed", "failed"))
        assertNotNull(why)
        assertTrue(why.contains("failed"), why)
    }

    @Test
    fun a_slow_start_is_not_mistaken_for_a_crash()
    {
        assertNull(
            check(
                UnitSample("activating", "start"),
                UnitSample("activating", "start"),
                UnitSample("activating", "start"),
                running(),
                running(),
                running(),
                trial = 5,
            ),
        )
    }

    @Test
    fun the_probe_is_called_once_per_second_of_the_trial()
    {
        var sleeps = 0
        val check = UnitStabilityCheck({ running() }, trialSeconds = 10, sleepSeconds = { sleeps += it })
        assertNull(check.verify("u"))
        assertEquals(10, sleeps)
    }
}
