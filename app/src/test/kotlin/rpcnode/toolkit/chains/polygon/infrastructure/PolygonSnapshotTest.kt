package rpcnode.toolkit.chains.polygon.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.chains.ScriptedSnapshots
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotPaths
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotSentinel
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.EvmSnapshotRunner

class PolygonSnapshotTest
{
    private fun paths(root: Path) = EvmSnapshotPaths(
        root = root,
        bins = emptyMap(),
        marker = root.resolve(".toolkit/evm-snapshot.done"),
        state = root.resolve(".toolkit/evm-snapshot-state.json"),
        logFile = root.resolve("logs/evm-snapshot.log"),
        script = root.resolve(".toolkit/evm-snapshot.sh"),
    )

    private fun script(env: String, params: Map<String, String> = emptyMap(), root: Path = Path.of("/data/nvme1/rpcnode/polygon/$env/bor")): String =
        PolygonSnapshot.snapshotScript(EvmSnapshotRef("polygon", env, "full", params), paths(root))

    private fun fixture(): Path
    {
        val f = Files.createTempFile("publicnode", ".html")
        Files.writeString(f, checkNotNull(javaClass.classLoader.getResourceAsStream("polygon/publicnode-snapshots-links.html")).readBytes().toString(Charsets.UTF_8))
        return f
    }

    /** The helper functions of the real script (everything before the restore flow), run against the real page links. */
    private fun latest(env: String, prefix: String): String
    {
        val text = script(env)
        val defs = text.substring(text.indexOf("PAGE="), text.indexOf("# --- restore"))
        val p = ProcessBuilder("bash", "-c", "set -u; $defs\nlatest_link '${fixture()}' '$prefix'").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        assertEquals(0, p.waitFor(), out)
        return out
    }

    @Test
    fun the_newest_link_of_each_family_is_picked_from_the_real_page_by_the_last_height()
    {
        assertEquals("https://snapshots.publicnode.com/polygon-amoy-heimdall-pruned-49342317-49342327.tar.lz4", latest("amoy", "polygon-amoy-heimdall-pruned"))
        assertEquals("https://snapshots.publicnode.com/polygon-amoy-bor-base-0-23132104.tar.lz4", latest("amoy", "polygon-amoy-bor-base"))
        assertEquals("https://snapshots.publicnode.com/polygon-amoy-bor-part-23132105-50285366.tar.lz4", latest("amoy", "polygon-amoy-bor-part"))
        // two mainnet Heimdall files are listed: the one with the higher height wins
        assertEquals("https://snapshots.publicnode.com/polygon-heimdall-pruned-55659336-55659346.tar.lz4", latest("mainnet", "polygon-heimdall-pruned"))
        assertEquals("https://snapshots.publicnode.com/polygon-bor-base-0-75817088.tar.lz4", latest("mainnet", "polygon-bor-base"))
        assertEquals("https://snapshots.publicnode.com/polygon-bor-part-75817089-95544728.tar.lz4", latest("mainnet", "polygon-bor-part"))
        // the families never mix: a mainnet prefix does not pick an amoy file, an amoy prefix does not pick a mainnet one,
        // and archive files are never taken for base / part / pruned
        for (prefix in listOf("polygon-heimdall-pruned", "polygon-bor-base", "polygon-bor-part"))
        {
            val link = latest("mainnet", prefix)
            assertTrue(link.isNotEmpty() && !link.contains("amoy") && !link.contains("archive"), link)
        }
        for (prefix in listOf("polygon-amoy-heimdall-pruned", "polygon-amoy-bor-base", "polygon-amoy-bor-part"))
        {
            val link = latest("amoy", prefix)
            assertTrue(link.contains("amoy") && !link.contains("archive"), link)
        }
        assertEquals("", latest("amoy", "polygon-amoy-heimdall-nonexistent"))
    }

    @Test
    fun the_pruned_bor_archive_is_never_restored()
    {
        // its chain freezer is empty and Bor 2.10.x refuses it ("ancient chain segments already extracted"): checked on both networks
        for (env in listOf("amoy", "mainnet"))
        {
            val families = checkNotNull(PolygonSnapshot.families(env))
            assertEquals(2, families.bor.size)
            assertTrue(families.bor.none { it.contains("pruned") || it.contains("archive") }, families.bor.toString())
            assertTrue(families.bor[0].endsWith("-base") && families.bor[1].endsWith("-part"), "base first, part second: ${families.bor}")
        }
    }

    @Test
    fun the_script_restores_heimdall_first_then_bor_into_the_node_layout_and_is_valid_bash()
    {
        val s = script("amoy", mapOf("dir_bor" to "/data/nvme1/rpcnode/polygon/amoy/bor", "dir_heimdall" to "/data/nvme1/rpcnode/polygon/amoy/heimdall"))
        assertFalse(s.contains("{{"), "no placeholder left")
        assertTrue(s.contains("HEIMDALL='/data/nvme1/rpcnode/polygon/amoy/heimdall'"))
        assertTrue(s.contains("BORDATA='/data/nvme1/rpcnode/polygon/amoy/bor/datadir/data'"), "Bor's chaindata is <datadir>/data/bor/chaindata")
        assertTrue(s.contains("HEIM_PREFIX='polygon-amoy-heimdall-pruned'") && s.contains("BOR_PREFIXES='polygon-amoy-bor-base polygon-amoy-bor-part'"))
        assertTrue(s.indexOf("Heimdall ---") < s.indexOf("Bor ---"), "Heimdall first, Bor second")
        assertTrue(s.contains("rm -rf \"\$HEIMDALL/data\"") && !s.contains("rm -rf \"\$HEIMDALL\"\n"), "only data/ of the Heimdall home is replaced, config/ and keys stay")
        assertTrue(s.contains("rm -rf \"\$BORDATA/bor/chaindata\" \"\$BORDATA/bor/triedb\""), "a stale triedb journal must not meet the new chaindata")
        assertTrue(s.contains("data/application.db") && s.contains("bor/chaindata"), "the archive layout is checked")
        assertTrue(s.contains("UNITS='rpcnode-polygon-amoy.service rpcnode-polygon-heimdall-amoy.service'"), "both units are stopped first")
        assertTrue(s.contains("touch \"\$MARKER\""))
        assertFalse(s.contains("docker", ignoreCase = true))
        val f = Files.createTempFile("polygon-snapshot", ".sh")
        Files.writeString(f, s)
        val p = ProcessBuilder("bash", "-n", f.toString()).redirectErrorStream(true).start()
        assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
    }

    @Test
    fun without_the_role_parameters_the_node_start_defaults_apply()
    {
        val s = script("mainnet", root = Path.of("/data/pol/mainnet/bor"))
        assertTrue(s.contains("HEIMDALL='/data/pol/mainnet/heimdall'"), "<parent of the bor dir>/heimdall, like PolygonNodeStart")
        assertTrue(s.contains("BORDATA='/data/pol/mainnet/bor/datadir/data'"))
        assertTrue(s.contains("HEIM_PREFIX='polygon-heimdall-pruned'") && s.contains("BOR_PREFIXES='polygon-bor-base polygon-bor-part'"))
    }

    @Test
    fun unsafe_or_unknown_input_is_refused_before_a_script_exists()
    {
        assertFailsWith<IllegalStateException> { script("sepolia") }
        assertFailsWith<IllegalStateException> { script("amoy", mapOf("dir_heimdall" to "/x", "dir_bor" to "/data/a/b")) }
        // a path with a space or ".." is ignored (defaults), never written into the script
        val s = script("amoy", mapOf("dir_heimdall" to "/data/a b/../c", "dir_bor" to "/data/x/bor"))
        assertTrue(s.contains("HEIMDALL='/data/x/heimdall'"), s)
    }

    @Test
    fun the_panel_resolves_the_sentinel_for_both_envs_and_nothing_else() = runTest {
        val r = PolygonSnapshot.resolver()
        val amoy = assertNotNull(r.resolve(EnvId.AMOY, ""))
        assertEquals("evm-snapshot://polygon/amoy/full", amoy.url)
        assertEquals("evm-snapshot://polygon/mainnet/full", r.resolve(EnvId.MAINNET, "full")!!.url)
        assertNull(r.resolve(EnvId.AMOY, "archive"))
        assertNull(r.resolve(EnvId.AMOY, "pruned"), "the pruned Bor archive is not startable")
        assertNull(r.resolve(EnvId.SEPOLIA, "full"))
        assertTrue(EvmSnapshotSentinel.isSentinel(amoy.url))
    }

    @Test
    fun the_agent_finds_polygon_and_still_finds_the_evm_family()
    {
        val runner = EvmSnapshotRunner()
        val polygon = assertNotNull(runner.snapshotFor(EvmSnapshotRef("polygon", "amoy", "full")))
        assertEquals(emptyList(), polygon.snapshotBinaries("amoy"), "only host tools: curl, lz4, tar")
        assertNull(runner.profileFor(EvmSnapshotRef("polygon", "amoy", "full")), "polygon is not an EVM-family profile")
        assertNotNull(runner.snapshotFor(EvmSnapshotRef("sonic", "mainnet", "pruned")))
        assertNull(runner.snapshotFor(EvmSnapshotRef("nope", "mainnet", "x")))
        assertEquals(ScriptedSnapshots.ALL.size, ScriptedSnapshots.ALL.map { it.snapshotNetwork }.toSet().size, "one entry per network")
    }

    @Test
    fun a_network_yml_snapshot_type_exists_for_every_env_the_resolver_serves()
    {
        val yaml = checkNotNull(javaClass.classLoader.getResourceAsStream("chains/polygon/network.yml")).readBytes().toString(Charsets.UTF_8)
        assertEquals(2, Regex("(?m)^    snapshot: required$").findAll(yaml).count(), "mainnet and amoy")
        assertEquals(2, Regex("(?m)^      - id: full$").findAll(yaml).count())
        assertTrue(yaml.contains("hostPackages:") && yaml.contains("  - lz4"))
    }
}
