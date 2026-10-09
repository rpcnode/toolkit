package rpcnode.toolkit.chains.tron.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionSource

class TronNileArtifactUrlResolverTest
{
    private val resolver = TronNileArtifactUrlResolver()
    private val jar = ClientArtifactSpec(name = "FullNode.jar", role = ClientArtifactRole.ARTIFACT, urlTemplate = "unused")

    private fun spec(env: EnvId) = ClientProgramSpec(
        network = NetworkId.TRON,
        env = env,
        programId = "FullNode.jar",
        source = ClientVersionSource.Pinned(version = "x", tag = "x", label = "x"),
    )

    @Test
    fun builds_file_name_from_the_resolved_tag() = runTest {
        val tag = "GreatVoyage-Nile-v4.8.2.2-PQ1-build1"
        assertEquals(
            "https://github.com/tron-nile-testnet/nile-testnet/releases/download/$tag/FullNode-Nile-x64-4.8.2.2-pq1-build1.jar",
            resolver.resolve(spec(EnvId.NILE), jar, tag, tag, aarch64 = false),
        )
        assertEquals(
            "https://github.com/tron-nile-testnet/nile-testnet/releases/download/$tag/FullNode-Nile-aarch64-4.8.2.2-pq1-build1.jar",
            resolver.resolve(spec(EnvId.NILE), jar, tag, tag, aarch64 = true),
        )
    }

    @Test
    fun handles_tags_without_nile_infix() = runTest {
        val tag = "GreatVoyage-v4.8.2-build1"
        assertEquals(
            "https://github.com/tron-nile-testnet/nile-testnet/releases/download/$tag/FullNode-Nile-x64-4.8.2-build1.jar",
            resolver.resolve(spec(EnvId.NILE), jar, tag, tag, aarch64 = false),
        )
    }

    @Test
    fun ignores_other_envs() = runTest {
        val tag = "GreatVoyage-v4.8.2.1"
        assertNull(resolver.resolve(spec(EnvId.MAINNET), jar, tag, tag, aarch64 = false))
    }
}
