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
