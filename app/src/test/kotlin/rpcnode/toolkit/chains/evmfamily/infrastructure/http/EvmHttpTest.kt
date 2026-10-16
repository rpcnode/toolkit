package rpcnode.toolkit.chains.evmfamily.infrastructure.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumEthRpc
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotSentinel
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.GravityProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.FlareBootstrap
import rpcnode.toolkit.clients.application.GitHubRelease
import rpcnode.toolkit.clients.application.GitHubReleaseAsset
import rpcnode.toolkit.clients.application.version.GitHubAssetUrlResolver
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.catalog.domain.NetworkId

class EvmHttpTest
{
    // real answers of the public endpoints, 2026-10-13 / 14
    @Test
    fun eth_block_number_samples_parse()
    {
        assertEquals(0x4d34fd3L, EthereumEthRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"result":"0x4d34fd3"}"""))
        assertEquals(0x1f019L, EthereumEthRpc.parseHexInt64("0x1f019"))
        assertNull(EthereumEthRpc.parseBlockNumber("""{"jsonrpc":"2.0","id":1,"error":{"code":-32601}}"""))
        assertNull(EthereumEthRpc.parseBlockNumber(null))
    }

    @Test
    fun flare_bootstrap_answers_parse()
    {
        assertEquals("34.79.60.151:9651", FlareBootstrap.parseIp("""{"jsonrpc":"2.0","result":{"ip":"34.79.60.151:9651"},"id":1}"""))
        assertEquals(
            "NodeID-NCfvDEdDv8tuVDxWLB1ShJToQ7qAcca2B",
            FlareBootstrap.parseNodeId("""{"jsonrpc":"2.0","result":{"nodeID":"NodeID-NCfvDEdDv8tuVDxWLB1ShJToQ7qAcca2B"},"id":1}"""),
        )
        assertNull(FlareBootstrap.parseIp("""{"result":{"ip":"not an ip"}}"""))
        assertNull(FlareBootstrap.parseNodeId("""{"result":{"nodeID":"x"}}"""))
        assertNull(FlareBootstrap.parseIp("garbage"))
        assertEquals("https://flare-bootstrap.flare.network/ext/info", FlareBootstrap.infoUrl("flare-bootstrap.flare.network"))
    }

    @Test
    fun flare_bootstrap_falls_back_to_the_last_good_answer()
    {
        val dir = java.nio.file.Files.createTempDirectory("flare-bs")
        val peer = FlareBootstrap.Peer("1.2.3.4:9651", "NodeID-abcdefghijk")
        assertEquals(peer, FlareBootstrap.resolve(dir, "h") { peer })
        assertEquals(peer, FlareBootstrap.resolve(dir, "h") { null })
        assertNull(FlareBootstrap.resolve(java.nio.file.Files.createTempDirectory("flare-bs2"), "h") { null })
    }

    private val csv = """type,size_bytes,block_number,version,created_at,sha256,url,url_s3
beacon-kit-pruned,30272424,27428241,v1.4.1,2026-10-13 06:31:10,3c36,https://snapshots.berachain.com/snapshots/beacon-kit-pruned/beacon-kit-pruned-27428241-v1.4.1.tar.lz4,https://x/mainnet-beacond-pruned-latest.tar.lz4
beacon-kit-pruned,30158025,27385043,v1.4.1,2026-10-12 06:30:52,0afe,https://snapshots.berachain.com/snapshots/beacon-kit-pruned/beacon-kit-pruned-27385043-v1.4.1.tar.lz4,
beacon-kit-archive,336309619111,27428241,v1.4.1,2026-10-13 06:49:12,37ce,https://snapshots.berachain.com/snapshots/beacon-kit-archive/beacon-kit-archive-27428241-v1.4.1.tar.lz4,
reth-pruned,54006684985,27428241,v1.4.4,2026-10-13 06:31:10,9358,https://snapshots.berachain.com/snapshots/reth-pruned/bera-reth-pruned-27428241-v1.4.4.tar.lz4,
reth-pruned,53988799799,27385043,v1.4.4,2026-10-12 06:30:52,cbcc,https://snapshots.berachain.com/snapshots/reth-pruned/bera-reth-pruned-27385043-v1.4.4.tar.lz4,
reth-archive,1196401267629,27428241,v1.4.4,2026-10-13 06:45:09,4f7b,https://snapshots.berachain.com/snapshots/reth-archive/bera-reth-archive-27428241-v1.4.4.tar.lz4,
"""

    @Test
    fun berachain_picks_the_newest_block_that_has_both_halves()
    {
        val rows = BerachainSnapshotResolver.parseIndex(csv)
        assertEquals(6, rows.size)
        val pruned = assertNotNull(BerachainSnapshotResolver.pick(rows, "pruned"))
        assertEquals(27428241L, pruned.block)
        assertEquals(54006684985L, pruned.execution.bytes)
        assertEquals(30272424L, pruned.consensus.bytes)
        // archive: beacon-kit-archive and reth-archive both exist at the same block here
        assertEquals(27428241L, BerachainSnapshotResolver.pick(rows, "archive")?.block)
        // a block that only one side has is skipped
        val skewed = rows.filterNot { it.type == "reth-pruned" && it.block == 27428241L }
        assertEquals(27385043L, BerachainSnapshotResolver.pick(skewed, "pruned")?.block)
        assertNull(BerachainSnapshotResolver.pick(emptyList(), "pruned"))
        assertTrue(BerachainSnapshotResolver.parseIndex("nonsense").isEmpty())
        assertTrue(BerachainSnapshotResolver.parseIndex(null).isEmpty())
    }

    @Test
    fun berachain_rows_with_a_non_https_url_are_dropped()
    {
        val bad = "type,size_bytes,block_number,version,created_at,sha256,url,url_s3\nreth-pruned,5,1,v,t,aa,http://plain/x.tar.lz4,\nreth-pruned,5,2,v,t,aa,https://ok/x.tar.lz4,\n"
        assertEquals(listOf(2L), BerachainSnapshotResolver.parseIndex(bad).map { it.block })
    }

    @Test
    fun berachain_resolves_mainnet_to_a_sentinel_pair_and_bepolia_to_nothing() = runTest {
        val http = rpcnode.toolkit.shared.infrastructure.http.SimpleHttp(
            io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine {
                respond(csv, io.ktor.http.HttpStatusCode.OK)
            }),
        )
        val resolver = BerachainSnapshotResolver(http, "https://index.test/index.csv")
        val archive = assertNotNull(resolver.resolve(EnvId.MAINNET, "pruned"))
        val ref = assertNotNull(EvmSnapshotSentinel.parse(archive.url))
        assertEquals("berachain", ref.network)
        assertEquals("27428241", ref.param("block"))
        assertTrue(ref.param("el").endsWith("bera-reth-pruned-27428241-v1.4.4.tar.lz4"))
        assertEquals("3c36", ref.param("clsha"))
        assertEquals(54006684985L + 30272424L, archive.sizeBytes)
        assertNull(resolver.resolve(EnvId.BEPOLIA, "pruned"))
    }

    @Test
    fun sentinel_resolver_accepts_only_known_kinds() = runTest {
        val r = EvmSnapshotSentinelResolver("tempo", mapOf("mainnet" to setOf("archive", "minimal")), "archive")
        assertEquals("evm-snapshot://tempo/mainnet/archive", r.resolve(EnvId.MAINNET, "")!!.url)
        assertEquals("evm-snapshot://tempo/mainnet/minimal", r.resolve(EnvId.MAINNET, "MINIMAL")!!.url)
        assertNull(r.resolve(EnvId.MAINNET, "full"))
        assertNull(r.resolve(EnvId.TESTNET, "archive"))
    }

    private val manifest = """{"schema_version":1,"network":"mainnet","data_type":"pruning","latest":"mainnet/pruning/kaia-mainnet-pruning-chaindata-20261012010011.tar.zst",
        "snapshots":[{"name":"a","key":"k","url":"https://w.dev/mainnet/pruning/kaia-mainnet-pruning-chaindata-20261012010011.tar.zst","bytes":2287578927937,"uncompressed_bytes":2790693642161,"md5":"73ab","sha256":null,"created":"x","chunks":null},
        {"name":"b","key":"k2","url":"https://w.dev/mainnet/pruning/kaia-mainnet-pruning-chaindata-20261005010012.tar.zst","bytes":2284569665343,"uncompressed_bytes":1,"md5":"72a6","sha256":null,"created":"x","chunks":null}]}"""

    @Test
    fun kaia_manifest_size_is_the_entry_of_the_resolved_url()
    {
        assertEquals(2287578927937L, KaiaSnapshotResolver.parseBytes(manifest, "https://w.dev/mainnet/pruning/kaia-mainnet-pruning-chaindata-20261012010011.tar.zst"))
        assertEquals(2284569665343L, KaiaSnapshotResolver.parseBytes(manifest, "https://w.dev/mainnet/pruning/kaia-mainnet-pruning-chaindata-20261005010012.tar.zst"))
        assertNull(KaiaSnapshotResolver.parseBytes(manifest, "https://other/x.tar.zst"))
        assertNull(KaiaSnapshotResolver.parseBytes("not json", "x"))
    }

    @Test
    fun kaia_resolves_the_latest_txt_and_streams_it() = runTest {
        val http = rpcnode.toolkit.shared.infrastructure.http.SimpleHttp(
            io.ktor.client.HttpClient(io.ktor.client.engine.mock.MockEngine { req ->
                if (req.url.encodedPath.endsWith("latest.txt")) respond("https://w.dev/mainnet/pruning/kaia-mainnet-pruning-chaindata-20261012010011.tar.zst\n", io.ktor.http.HttpStatusCode.OK)
                else respond(manifest, io.ktor.http.HttpStatusCode.OK)
            }),
        )
        val a = assertNotNull(KaiaSnapshotResolver(http, "https://snap.test").resolve(EnvId.MAINNET, "pruning"))
        assertTrue(a.streamUnpack && a.url.endsWith(".tar.zst") && a.sizeBytes == 2287578927937L)
        assertNull(KaiaSnapshotResolver(http, "https://snap.test").resolve(EnvId.TESTNET, "pruning"))
    }

    private fun kaiaSpec() = ClientProgramSpec(
        network = NetworkId.KAIA, env = EnvId.MAINNET, programId = "ken",
        source = ClientVersionSource.GitHubRelease("kaiachain/kaia"),
    )

    private val kaiaArtifact = ClientArtifactSpec(
        "ken-linux-amd64.tar.gz", ClientArtifactRole.ARTIFACT,
        "https://packages.kaia.io/kaia/{tag}/ken-{tag}-0-linux-amd64.tar.gz",
        "https://packages.kaia.io/kaia/{tag}/ken-{tag}-0-linux-arm64.tar.gz",
    )

    @Test
    fun kaia_package_probe_skips_the_one_byte_stub() = runTest {
        val seen = mutableListOf<String>()
        val resolver = KaiaPackageUrlResolver(contentLength = { url -> seen += url; if ("-1-linux" in url) 58_181_931L else 1L })
        val url = resolver.resolve(kaiaSpec(), kaiaArtifact, "2.2.2", "v2.2.2", aarch64 = false)
        assertEquals("https://packages.kaia.io/kaia/v2.2.2/ken-v2.2.2-1-linux-amd64.tar.gz", url)
        assertEquals(2, seen.size)
        val arm = resolver.resolve(kaiaSpec(), kaiaArtifact, "2.2.2", "v2.2.2", aarch64 = true)
        assertTrue(arm!!.endsWith("ken-v2.2.2-1-linux-arm64.tar.gz"))
    }

    @Test
    fun kaia_package_probe_fails_clearly_when_the_release_has_no_package() = runTest {
        val resolver = KaiaPackageUrlResolver(contentLength = { 1L })
        val e = assertFailsWith<IllegalStateException> { resolver.resolve(kaiaSpec(), kaiaArtifact, "2.2.1", "v2.2.1", aarch64 = false) }
        assertTrue("no ken package for v2.2.1" in e.message.orEmpty())
        // other networks are not touched
        assertNull(resolver.resolve(kaiaSpec().copy(network = NetworkId.SONIC), kaiaArtifact, "1", "v1", false))
    }

    @Test
    fun github_asset_glob_picks_the_nethermind_zip_and_not_the_signature()
    {
        val release = GitHubRelease(
            tag = "2.1.0", version = "2.1.0",
            assets = listOf(
                GitHubReleaseAsset("nethermind-2.1.0-b3e7e84c-linux-x64.zip.asc", "https://g/x64.asc"),
                GitHubReleaseAsset("nethermind-2.1.0-b3e7e84c-linux-arm64.zip", "https://g/arm64.zip"),
                GitHubReleaseAsset("nethermind-2.1.0-b3e7e84c-linux-x64.zip", "https://g/x64.zip"),
                GitHubReleaseAsset("nethermind-2.1.0-b3e7e84c-macos-arm64.zip", "https://g/mac.zip"),
            ),
        )
        assertEquals("https://g/x64.zip", GitHubAssetUrlResolver.pick(release, "2.1.0", "nethermind-*-linux-x64.zip"))
        assertEquals("https://g/arm64.zip", GitHubAssetUrlResolver.pick(release, "2.1.0", "nethermind-*-linux-arm64.zip"))
        assertNull(GitHubAssetUrlResolver.pick(release, "2.0.0", "nethermind-*-linux-x64.zip"), "another tag")
        assertNull(GitHubAssetUrlResolver.pick(release, "2.1.0", "nope-*.zip"))
        assertEquals("nethermind-*-linux-x64.zip", GitHubAssetUrlResolver.globOf("github-asset:nethermind-*-linux-x64.zip"))
        assertNull(GitHubAssetUrlResolver.globOf("https://x/y.zip"))
    }

    @Test
    fun gravity_snapshots_are_stable_tar_urls_streamed_in_one_pass() = runTest {
        val resolver = GravityProfile.PROFILE.snapshot!!()
        val m = assertNotNull(resolver.resolve(EnvId.MAINNET, "pfn"))
        assertEquals(GravityProfile.MAINNET_SNAPSHOT, m.url)
        assertTrue(m.streamUnpack)
        assertNull(resolver.resolve(EnvId.parse("nope")!!, "pfn"))
    }
}
