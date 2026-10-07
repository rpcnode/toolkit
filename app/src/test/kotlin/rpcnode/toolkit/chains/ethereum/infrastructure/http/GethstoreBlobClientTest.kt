package rpcnode.toolkit.chains.ethereum.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GethstoreBlobClientTest
{
    @Test
    fun pick_tarball_skips_asc_and_unstable()
    {
        val client = GethstoreBlobClient()
        val names = listOf(
            "geth-linux-amd64-1.17.5-9621c6ad.tar.gz.asc",
            "geth-linux-amd64-1.17.5-unstable-aaaaaaa.tar.gz",
            "geth-linux-amd64-1.17.5-9621c6ad.tar.gz",
            "geth-linux-arm64-1.17.5-9621c6ad.tar.gz",
        )
        assertEquals(
            "geth-linux-amd64-1.17.5-9621c6ad.tar.gz",
            client.pickTarball(names, "amd64", "1.17.5"),
        )
        assertEquals(
            "geth-linux-arm64-1.17.5-9621c6ad.tar.gz",
            client.pickTarball(names, "arm64", "1.17.5"),
        )
        assertNull(client.pickTarball(names, "amd64", "9.9.9"))
    }
}
