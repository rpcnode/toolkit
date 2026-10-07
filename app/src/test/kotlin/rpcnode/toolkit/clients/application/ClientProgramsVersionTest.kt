package rpcnode.toolkit.clients.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientVersionPin

class ClientProgramsVersionTest
{
    @Test
    fun single_program_stays_bare_version()
    {
        assertEquals("4.8.2", formatClientProgramsVersion(mapOf("FullNode.jar" to "4.8.2")))
    }

    @Test
    fun multi_program_orders_geth_first()
    {
        assertEquals(
            "geth 1.17.5 · lighthouse 8.2.2",
            formatClientProgramsVersion(
                mapOf("lighthouse" to "8.2.2", "geth" to "1.17.5"),
            ),
        )
    }

    @Test
    fun does_not_flag_lighthouse_local_against_geth_pin()
    {
        // Legacy VERSION overwritten by lighthouse download; pins are current.
        val pins = listOf(
            ClientVersionPin(
                network = NetworkId.ETHEREUM,
                env = EnvId.SEPOLIA,
                program = "geth",
                currentVersion = "1.17.5",
                latestVersion = "1.17.5",
            ),
            ClientVersionPin(
                network = NetworkId.ETHEREUM,
                env = EnvId.SEPOLIA,
                program = "lighthouse",
                currentVersion = "8.2.2",
                latestVersion = "8.2.2",
            ),
        )
        assertFalse(pinsUpdateAvailable("8.2.2", pins, primary = "geth"))
        assertFalse(pinsUpdateAvailable("1.17.5", pins, primary = "geth"))
        assertFalse(
            pinsUpdateAvailable("geth 1.17.5 · lighthouse 8.2.2", pins, primary = "geth"),
        )
    }

    @Test
    fun flags_when_one_program_is_behind()
    {
        val pins = listOf(
            ClientVersionPin(
                network = NetworkId.ETHEREUM,
                env = EnvId.SEPOLIA,
                program = "geth",
                currentVersion = "1.17.5",
                latestVersion = "1.17.5",
            ),
            ClientVersionPin(
                network = NetworkId.ETHEREUM,
                env = EnvId.SEPOLIA,
                program = "lighthouse",
                currentVersion = "8.1.0",
                latestVersion = "8.2.2",
            ),
        )
        assertTrue(
            pinsUpdateAvailable("geth 1.17.5 · lighthouse 8.1.0", pins, primary = "geth"),
        )
        assertFalse(
            pinsUpdateAvailable("geth 1.17.5 · lighthouse 8.2.2", pins, primary = "geth"),
        )
    }

    @Test
    fun clientProgramsUpdateAvailable_ignores_cross_program_bare_compare()
    {
        assertFalse(clientProgramsUpdateAvailable("geth 1.17.5", "lighthouse 8.2.2"))
        assertTrue(
            clientProgramsUpdateAvailable(
                "geth 1.17.5 · lighthouse 8.1.0",
                "geth 1.17.5 · lighthouse 8.2.2",
            ),
        )
        assertFalse(
            clientProgramsUpdateAvailable(
                "geth 1.17.5 · lighthouse 8.2.2",
                "geth 1.17.5 · lighthouse 8.2.2",
            ),
        )
    }

    @Test
    fun formatPinsVersion_builds_composite_latest()
    {
        val pins = listOf(
            ClientVersionPin(
                network = NetworkId.ETHEREUM,
                env = EnvId.SEPOLIA,
                program = "lighthouse",
                currentVersion = "8.2.2",
                latestVersion = "8.2.2",
            ),
            ClientVersionPin(
                network = NetworkId.ETHEREUM,
                env = EnvId.SEPOLIA,
                program = "geth",
                currentVersion = "1.17.4",
                latestVersion = "1.17.5",
            ),
        )
        assertEquals("geth 1.17.5 · lighthouse 8.2.2", formatPinsVersion(pins, preferLatest = true))
        assertEquals("geth 1.17.4 · lighthouse 8.2.2", formatPinsVersion(pins, preferLatest = false))
    }
}
