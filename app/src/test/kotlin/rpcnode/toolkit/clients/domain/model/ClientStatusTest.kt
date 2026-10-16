package rpcnode.toolkit.clients.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ClientStatusTest
{
    @Test
    fun missing_when_never_downloaded()
    {
        assertEquals(ClientStatus.MISSING, ClientStatus.compute("", "", "", ""))
    }

    @Test
    fun wait_when_downloaded_but_never_probed()
    {
        assertEquals(ClientStatus.WAIT, ClientStatus.compute("1.0", "", "", ""))
    }

    @Test
    fun ok_when_current_matches_latest()
    {
        assertEquals(ClientStatus.OK, ClientStatus.compute("1.0", "1.0", "", ""))
        assertEquals(ClientStatus.OK, ClientStatus.compute("v1.0", "1.0", "", ""))
    }

    @Test
    fun stale_when_current_lags_latest()
    {
        assertEquals(ClientStatus.STALE, ClientStatus.compute("1.0", "1.1", "", ""))
    }

    @Test
    fun ok_when_installed_build_is_ahead_of_latest()
    {
        // bsc/mainnet: installed 1.8.0-alpha, latest release with the config zip is 1.7.8 — not an update
        assertEquals(ClientStatus.OK, ClientStatus.compute("1.8.0-alpha", "1.7.8", "", ""))
        assertEquals(ClientStatus.PIN, ClientStatus.compute("1.8.0-alpha", "1.7.8", "pinned", ""))
        assertEquals(ClientStatus.STALE, ClientStatus.compute("1.7.8", "1.8.0-alpha", "", ""))
    }

    @Test
    fun isNewerClientRelease_compares_the_leading_number()
    {
        assertEquals(false, isNewerClientRelease("1.7.8", "1.8.0-alpha"))
        assertEquals(false, isNewerClientRelease("v1.7.8", "1.8.0"))
        assertEquals(false, isNewerClientRelease("1.8.0", "v1.8.0"))
        assertEquals(true, isNewerClientRelease("1.8.0", "1.8.0-alpha"))
        assertEquals(true, isNewerClientRelease("1.10.0", "1.9.9"))
        assertEquals(true, isNewerClientRelease("1.8.1", "1.8"))
        assertEquals(false, isNewerClientRelease("1.8", "1.8.1"))
        assertEquals(true, isNewerClientRelease("nile-v4.8.2.3-pq1-build2", "nile-v4.8.2.3-pq1-build1"))
        assertEquals(false, isNewerClientRelease("GreatVoyage-v4.8.2", "GreatVoyage-v4.8.2.1"))
        assertEquals(true, isNewerClientRelease("nightly", "stable"))
        assertEquals(true, isNewerClientRelease("1.8.0", "1.8.0-alpha2"))
        assertEquals(false, isNewerClientRelease("1.8.0-rc1", "1.8.0"))
        assertEquals(true, isNewerClientRelease("1.8.0-rc1", "1.8.0-beta2"))
        assertEquals(false, isNewerClientRelease("1.8.0.0", "1.8.0"))
    }

    @Test
    fun fail_when_probe_error_present_regardless_of_versions()
    {
        assertEquals(ClientStatus.FAIL, ClientStatus.compute("1.0", "1.1", "", "boom"))
        assertEquals(ClientStatus.FAIL, ClientStatus.compute("", "", "", "boom"))
    }

    @Test
    fun pin_when_skip_reason_present_and_up_to_date()
    {
        assertEquals(ClientStatus.PIN, ClientStatus.compute("1.0", "1.0", "host pin", ""))
        assertEquals(ClientStatus.PIN, ClientStatus.compute("1.0", "", "host pin", ""))
    }

    @Test
    fun stale_beats_pin_when_a_skipped_program_still_lags_latest()
    {
        assertEquals(ClientStatus.STALE, ClientStatus.compute("1.0", "1.1", "host pin", ""))
    }

    @Test
    fun parse_round_trips_every_value()
    {
        for (status in ClientStatus.entries)
        {
            assertEquals(status, ClientStatus.parse(status.value))
        }
    }
}
