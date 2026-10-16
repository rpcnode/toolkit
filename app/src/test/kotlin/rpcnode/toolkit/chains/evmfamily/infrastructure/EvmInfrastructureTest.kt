package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.start.EvmNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext

class EvmInfrastructureTest
{
    private fun tmp(): Path = Files.createTempDirectory("evm-infra")

    private fun zip(file: Path, entries: Map<String, String>)
    {
        ZipOutputStream(Files.newOutputStream(file)).use { z ->
            for ((name, body) in entries)
            {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
    }

    private fun tarGz(file: Path, entries: Map<String, String>)
    {
        val src = Files.createTempDirectory("evm-tar-src")
        for ((name, body) in entries)
        {
            Files.createDirectories(src.resolve(name).parent)
            Files.writeString(src.resolve(name), body)
        }
        val p = ProcessBuilder("tar", "-czf", file.toString(), "-C", src.toString(), ".").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        check(p.waitFor() == 0)
    }

    @Test
    fun raw_binary_is_copied_executable_and_kept_when_fresh()
    {
        val root = tmp()
        Files.writeString(root.resolve("sonicd"), "ELF")
        val r = EvmHostBinaries.ensure(root, listOf(EvmBinary("sonicd", "sonicd", EvmBinary.Kind.RAW)))
        val bin = assertIs<EvmHostBinaries.Result.Ok>(r).bins.getValue("sonicd")
        assertEquals(root.resolve("bin/sonicd"), bin)
        assertTrue(Files.isExecutable(bin))
        assertTrue(Files.exists(root.resolve("sonicd")), "the synced file stays (a snapshot wipe keeps top-level client files)")
        // a second run does not need the source
        Files.delete(root.resolve("sonicd"))
        assertIs<EvmHostBinaries.Result.Ok>(EvmHostBinaries.ensure(root, listOf(EvmBinary("sonicd", "sonicd", EvmBinary.Kind.RAW))))
    }

    @Test
    fun tar_member_is_found_by_glob_and_renamed()
    {
        val root = tmp()
        tarGz(root.resolve("tempo-x86_64.tar.gz"), mapOf("tempo-v1.16.0-x86_64-unknown-linux-gnu" to "ELF"))
        val b = EvmBinary("tempo", "tempo-*.tar.gz", EvmBinary.Kind.TAR_MEMBER, member = "tempo*")
        val ok = assertIs<EvmHostBinaries.Result.Ok>(EvmHostBinaries.ensure(root, listOf(b)))
        assertEquals("ELF", Files.readString(ok.bins.getValue("tempo")))
        // kaia style: a nested directory
        val root2 = tmp()
        tarGz(root2.resolve("ken-linux-amd64.tar.gz"), mapOf("ken-linux-amd64/bin/ken" to "K", "ken-linux-amd64/bin/kend" to "D"))
        val ok2 = assertIs<EvmHostBinaries.Result.Ok>(EvmHostBinaries.ensure(root2, listOf(EvmBinary("ken", "ken-linux-*.tar.gz", EvmBinary.Kind.TAR_MEMBER))))
        assertEquals("K", Files.readString(ok2.bins.getValue("ken")))
    }

    @Test
    fun zip_bundle_unpacks_the_whole_app_with_its_configs()
    {
        val root = tmp()
        zip(root.resolve("nethermind-linux-x64.zip"), mapOf("nethermind" to "ELF", "configs/gnosis.json" to "{}", "plugins/a.dll" to "x", "../evil" to "no"))
        val ok = assertIs<EvmHostBinaries.Result.Ok>(EvmHostBinaries.ensure(root, listOf(EvmBinary("nethermind", "nethermind-linux-*.zip", EvmBinary.Kind.ZIP_BUNDLE))))
        val bin = ok.bins.getValue("nethermind")
        assertTrue(Files.isExecutable(bin))
        assertTrue(Files.isRegularFile(root.resolve("bin/configs/gnosis.json")))
        assertTrue(Files.notExists(root.resolve("evil")) && Files.notExists(root.resolve("bin/../evil")), "path traversal is skipped")
    }

    @Test
    fun zip_member_for_flare()
    {
        val root = tmp()
        zip(root.resolve("go-flare-linux-amd64.zip"), mapOf("avalanchego" to "ELF"))
        val ok = assertIs<EvmHostBinaries.Result.Ok>(EvmHostBinaries.ensure(root, listOf(EvmBinary("avalanchego", "go-flare-*.zip", EvmBinary.Kind.ZIP_MEMBER))))
        assertEquals("ELF", Files.readString(ok.bins.getValue("avalanchego")))
    }

    @Test
    fun missing_sources_fail_with_a_sync_hint()
    {
        val r = EvmHostBinaries.ensure(tmp(), listOf(EvmBinary("x", "x-*.zip", EvmBinary.Kind.ZIP_BUNDLE)))
        assertTrue(assertIs<EvmHostBinaries.Result.Failed>(r).detail.contains("sync clients"))
    }

    @Test
    fun jwt_is_created_once_with_owner_only_permissions()
    {
        val root = tmp()
        val a = EvmJwt.ensure(root)
        val first = Files.readString(a).trim()
        assertTrue(Regex("[0-9a-f]{64}").matches(first))
        assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(a))
        assertEquals(first, Files.readString(EvmJwt.ensure(root)).trim())
        assertEquals(root.resolve(".toolkit/jwt.hex"), a)
    }

    @Test
    fun launch_options_round_trip_through_the_args()
    {
        val o = EvmLaunchOptions("mainnet", "archive", "pruned", mapOf("execution" to "/data/a", "consensus" to "/data/b"))
        assertEquals(o, EvmLaunchOptions.parse(o.toArgs()))
        assertEquals("archive", EvmLaunchOptions.modeFrom("""{"node":"archive"}"""))
        assertEquals("full", EvmLaunchOptions.modeFrom("""{"node":"weird"}"""))
        assertEquals("pruned", EvmLaunchOptions.snapshotFrom("""{"snapshot":"Pruned"}"""))
        // relative or traversing dirs from the wire are dropped
        assertEquals(emptyMap(), EvmLaunchOptions.parse(listOf("--toolkit-dir-x=../etc", "--toolkit-dir-y=rel")).dirs)
    }

    @Test
    fun snapshot_sentinel_round_trips_with_params()
    {
        val ref = EvmSnapshotRef("berachain", "mainnet", "pruned", mapOf("el" to "https://a/b.tar.lz4?x=1&y=2", "block" to "27428241"))
        val url = EvmSnapshotSentinel.url(ref)
        assertTrue(EvmSnapshotSentinel.isSentinel(url))
        assertEquals(ref, EvmSnapshotSentinel.parse(url))
        assertNull(EvmSnapshotSentinel.parse("https://x/y.tar"))
        assertNull(EvmSnapshotSentinel.parse("evm-snapshot://only/two"))
        assertEquals("evm-snapshot://sonic/mainnet/pruned", EvmSnapshotSentinel.url(EvmSnapshotRef("sonic", "mainnet", "pruned")))
    }

    @Test
    fun start_plan_carries_env_options_and_every_disk_role()
    {
        val profile = EvmProfiles.ALL.first { it.network == NetworkId.GNOSIS }
        val plan = EvmNodeStart(profile).plan(
            ChainNodeStartContext(
                network = NetworkId.GNOSIS,
                env = "chiado",
                program = "nethermind",
                configFile = null,
                nodeDir = "/data/rpcnode/gnosis/chiado/execution",
                diskLayoutJson = """{"roles":[{"id":"execution","dir":"/d1/execution"},{"id":"consensus","dir":"/d2/consensus"}]}""",
                installOptionsJson = """{"node":"archive"}""",
            ),
        )
        assertEquals("bin/nethermind", plan.launch.entry)
        assertEquals("logs/gnosis.log", plan.launch.logFile)
        assertEquals("eth_rpc", plan.height.kind)
        assertEquals("http", plan.height.portRole)
        val parsed = EvmLaunchOptions.parse(plan.launch.args)
        assertEquals("chiado", parsed.env)
        assertEquals("archive", parsed.mode)
        assertEquals(mapOf("execution" to "/d1/execution", "consensus" to "/d2/consensus"), parsed.dirs)
    }

    @Test
    fun an_unknown_env_falls_back_to_the_first_and_ports_follow_the_block()
    {
        val p = EvmProfiles.ALL.first { it.network == NetworkId.SONIC }
        assertEquals("mainnet", p.env("nope").id)
        assertEquals(42442, p.port(p.env("mainnet"), "p2p"))
        assertEquals(42450, p.port(p.env("testnet"), "http"))
        assertNotNull(p.heightProbe())
    }

    @Test
    fun host_ram_is_read_from_meminfo()
    {
        val f = tmp().resolve("meminfo")
        Files.writeString(f, "MemTotal:       65843920 kB\nMemFree: 1 kB\n")
        assertEquals(62, EvmHostFacts.memTotalGiB(f))
        assertNull(EvmHostFacts.memTotalGiB(tmp().resolve("absent")))
        assertEquals(ByteArrayOutputStream().size(), 0)
    }
}
