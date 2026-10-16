package rpcnode.toolkit.chains.stellar.infrastructure.http

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec

class StellarAptIndexTest
{
    private val packages = """
Package: stellar-core
Version: 29.0.0-3589.4eb833373.noble
Filename: pool/noble/s/st/stellar-core_29.0.0-3589.4eb833373.noble_amd64.deb
Size: 9684566
SHA256: ff4b

Package: stellar-core
Version: 28.0.0-3494.a9b861321.noble
Filename: pool/noble/s/st/stellar-core_28.0.0-3494.a9b861321.noble_amd64.deb

Package: stellar-rpc
Version: 29.0.1-213
Filename: pool/focal/s/st/stellar-rpc_29.0.1-213_amd64.deb
Size: 35909040
SHA256: a935

Package: stellar-rpc
Version: 29.0.0~vnext-209
Filename: pool/focal/s/st/stellar-rpc_29.0.0~vnext-209_amd64.deb

Package: stellar-rpc
Version: 29.0.0-210
Filename: pool/focal/s/st/stellar-rpc_29.0.0-210_amd64.deb
""".trimStart()

    private fun index(text: String? = packages) =
        StellarAptIndex(fetch = { if (text == null) null else ByteArrayInputStream(text.toByteArray()) })

    @Test
    fun picks_the_newest_stable_package_regardless_of_order() = runTest {
        val rpc = index().newest("stellar-rpc")!!
        assertEquals("29.0.1-213", rpc.version)
        assertEquals(35_909_040L, rpc.size)
        assertTrue(index().newest("stellar-core")!!.filename.endsWith("29.0.0-3589.4eb833373.noble_amd64.deb"))
        assertNull(index().newest("nothing"))
        assertNull(index(null).newest("stellar-rpc"))
    }

    @Test
    fun version_keys_compare_numerically()
    {
        assertEquals(listOf(29L, 0L, 1L, 213L), StellarAptIndex.versionKey("29.0.1-213"))
        assertEquals(listOf(28L, 0L, 1L, 3508L), StellarAptIndex.versionKey("28.0.1-3508.947aad841.jammy"))
        assertEquals(listOf(29L, 0L, 0L), StellarAptIndex.versionKey("29.0.0~vnext-209"))
    }

    @Test
    fun release_resolver_follows_stellar_rpc() = runTest {
        val resolver = StellarClientReleaseResolver(index())
        val r = resolver.resolve(EnvId.MAINNET)!!
        assertEquals("29.0.1", r.version)
        assertEquals("v29.0.1", r.tag)
        assertEquals("stellar/stellar-rpc", r.sourceLabel)
        assertNull(resolver.resolve(EnvId.FUJI))
        assertNull(StellarClientReleaseResolver(index(null)).resolve(EnvId.MAINNET))
    }

    private fun spec(network: NetworkId) = ClientProgramSpec(
        network = network,
        env = EnvId.MAINNET,
        programId = "stellar-rpc",
        source = ClientVersionSource.Pinned("1", "v1", "x"),
    )

    private fun artifact(template: String) =
        ClientArtifactSpec(name = "x.deb", role = ClientArtifactRole.ARTIFACT, urlTemplate = template)

    @Test
    fun artifact_markers_resolve_to_apt_urls() = runTest {
        val resolver = StellarArtifactUrlResolver(index())
        assertEquals(
            "https://apt.stellar.org/pool/focal/s/st/stellar-rpc_29.0.1-213_amd64.deb",
            resolver.resolve(spec(NetworkId.STELLAR), artifact("stellar-apt:stellar-rpc"), "29.0.1", "v29.0.1", false),
        )
        assertEquals(
            "https://apt.stellar.org/pool/noble/s/st/stellar-core_29.0.0-3589.4eb833373.noble_amd64.deb",
            resolver.resolve(spec(NetworkId.STELLAR), artifact("stellar-apt:stellar-core:noble"), "29.0.1", "v29.0.1", false),
        )
        assertNull(resolver.resolve(spec(NetworkId.STELLAR), artifact("https://raw.example/x.cfg"), "1", "v1", false))
        assertNull(resolver.resolve(spec(NetworkId.ETC), artifact("stellar-apt:stellar-rpc"), "1", "v1", false))
        val missing = runCatching {
            resolver.resolve(spec(NetworkId.STELLAR), artifact("stellar-apt:stellar-nothing"), "1", "v1", false)
        }
        assertTrue(missing.exceptionOrNull() is IllegalStateException)
    }
}
