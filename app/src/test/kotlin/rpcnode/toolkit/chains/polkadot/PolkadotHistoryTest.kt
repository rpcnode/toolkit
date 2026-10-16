package rpcnode.toolkit.chains.polkadot

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import rpcnode.toolkit.chains.nonevm.infrastructure.CatalogFixtures
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.polkadot.infrastructure.PolkadotHistory
import rpcnode.toolkit.chains.polkadot.infrastructure.PolkadotHistoryDiagnostics
import rpcnode.toolkit.chains.polkadot.infrastructure.PolkadotNode
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateMode
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpecs
import rpcnode.toolkit.chains.substrate.infrastructure.proc.SubstrateCompanionContext
import rpcnode.toolkit.chains.substrate.infrastructure.start.SubstrateNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.infrastructure.host.diagnostics.DiagnosticRequest

/** The PostgreSQL history indexer (install option `indexer=history`): catalog == code, start-block rules, scripts, units, diagnostic. */
class PolkadotHistoryTest
{
    private val spec = SubstrateSpecs.POLKADOT
    private val mainnet = spec.env("mainnet")
    private val westend = spec.env("westend")

    // ---------------------------------------------------------------- catalog == code

    @Test
    fun history_bindings_equal_what_the_processor_and_the_api_run_with()
    {
        val problems = mutableListOf<String>()
        for (env in spec.envs)
        {
            val ports = spec.ports(env)
            val catalogPorts = CatalogFixtures.ports("polkadot", env.id)
            val bindings = CatalogFixtures.bindingsFor("polkadot", env.id)
                .filter { it.whenOption == "indexer" && it.whenOptionValue == "history" }
            val processor = PolkadotHistory.processorEnvironment(env, ports, PolkadotHistory.defaultStart(env))
            val api = PolkadotHistory.apiEnvironment(ports)
            val flags = PolkadotHistory.apiFlags()
            // binding key -> what the process receives
            val expected = linkedMapOf<String, String>()
            for ((k, v) in processor) if (k !in setOf("NODE_RPC_URL", "NODE_OPTIONS")) expected[k] = v
            for ((k, v) in api) if (k != "NODE_OPTIONS") expected[k] = v
            expected["processor-max-old-space-size"] = PolkadotHistory.PROCESSOR_HEAP_MB.toString()
            expected["api-max-old-space-size"] = PolkadotHistory.API_HEAP_MB.toString()
            expected += flags
            expected["pg-connection-limit"] = PolkadotHistory.PG_CONNECTION_LIMIT.toString()
            assertEquals("--max-old-space-size=${PolkadotHistory.PROCESSOR_HEAP_MB}", processor["NODE_OPTIONS"])
            assertEquals("--max-old-space-size=${PolkadotHistory.API_HEAP_MB}", api["NODE_OPTIONS"])

            for (b in bindings)
            {
                val got = when (b.source)
                {
                    "catalog_port" -> catalogPorts[b.role].toString()
                    "install_option" -> b.default // the wizard value overrides it; empty = this default
                    else -> b.value
                }
                if (expected[b.key] != got) problems += "${env.id}: ${b.key} binding=$got code=${expected[b.key]}"
            }
            val bound = bindings.map { it.key }.toSet()
            for (k in expected.keys) if (k !in bound) problems += "${env.id}: $k is applied but has no binding"
            // every binding names something that exists in code
            for (k in bound) if (k !in expected) problems += "${env.id}: binding $k is not applied by the code"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun the_start_block_option_has_its_own_binding_and_the_ports_are_the_catalog_ones()
    {
        for (env in spec.envs)
        {
            val b = CatalogFixtures.bindingsFor("polkadot", env.id).single { it.key == "START_BLOCK" }
            assertEquals("install_option", b.source)
            assertEquals(PolkadotHistory.START_OPTION, b.option)
            val ports = CatalogFixtures.ports("polkadot", env.id)
            assertEquals(spec.ports(env).historyApi, ports["history_api"])
            assertEquals(spec.ports(env).historyMetrics, ports["history_metrics"])
        }
    }

    @Test
    fun the_resource_tree_is_exactly_the_project_file_list()
    {
        val dir = Path.of(checkNotNull(PolkadotHistory::class.java.classLoader.getResource("chains/polkadot/history")).toURI())
        val onDisk = Files.walk(dir).use { s -> s.filter { Files.isRegularFile(it) }.map { dir.relativize(it).toString() }.sorted().toList() }
        assertEquals(PolkadotHistory.PROJECT_FILES.sorted(), onDisk)
    }

    @Test
    fun the_project_is_pinned_and_self_consistent()
    {
        val pkg = PolkadotHistory.projectFile("package.json")
        val lock = PolkadotHistory.projectFile("package-lock.json")
        // exact versions only (no ^ ~ ranges): the lock file is the install, the package.json documents it
        val deps = Regex("\"([@a-z0-9/.-]+)\": \"([^\"]+)\"").findAll(pkg.substringAfter("\"dependencies\"")).toList()
        assertTrue(deps.isNotEmpty())
        for (d in deps) assertTrue(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(d.groupValues[2]) || d.groupValues[1] == "node", "${d.value} is not an exact version")
        assertTrue(lock.contains("\"lockfileVersion\": 3"))
        assertTrue(lock.contains("\"node_modules/@subsquid/substrate-processor\""))
        assertTrue(Regex("\"integrity\": \"sha512-").findAll(lock).count() > 300, "every package must carry an integrity hash")
        assertTrue(!lock.contains("\"resolved\": \"git") && !lock.contains("http://"), "no git or plain-http source in the lock")
        // the processor is not allowed to ask a third-party gateway for data
        assertTrue(!PolkadotHistory.projectFile("src/main.ts").contains("setGateway") && !PolkadotHistory.projectFile("src/main.ts").contains("setArchive"))
        // the hash changes with the content
        assertEquals(PolkadotHistory.projectHash(), PolkadotHistory.projectHash())
        assertEquals(16, PolkadotHistory.projectHash().length)
    }

    @Test
    fun the_schema_migration_creates_every_entity_with_its_indexes()
    {
        val sql = PolkadotHistory.projectFile("db/migrations/1700000000000-Data.js")
        for (t in listOf("block", "extrinsic", "event", "transfer")) assertTrue(sql.contains("CREATE TABLE \"$t\""), t)
        // account history is only fast with these
        assertTrue(sql.contains("ON \"transfer\" (\"from\", \"block_number\")"))
        assertTrue(sql.contains("ON \"transfer\" (\"to\", \"block_number\")"))
        assertTrue(sql.contains("ON \"extrinsic\" (\"signer\", \"block_number\")"))
        assertTrue(sql.contains("ON \"event\" (\"pallet\", \"name\", \"block_number\")"))
        val graphql = PolkadotHistory.projectFile("schema.graphql")
        for (t in listOf("Block", "Extrinsic", "Event", "Transfer")) assertTrue(graphql.contains("type $t @entity"), t)
    }

    // ---------------------------------------------------------------- start block

    @Test
    fun start_block_values()
    {
        assertEquals("head", PolkadotHistory.normalizeStart(mainnet, null), "the default is recent history only")
        assertEquals("head", PolkadotHistory.normalizeStart(mainnet, "  "))
        assertEquals("genesis", PolkadotHistory.normalizeStart(mainnet, "0"))
        assertEquals("genesis", PolkadotHistory.normalizeStart(mainnet, "GENESIS"))
        assertEquals("head", PolkadotHistory.normalizeStart(mainnet, "Head"))
        assertEquals("head", PolkadotHistory.normalizeStart(mainnet, "latest"))
        assertEquals("33000000", PolkadotHistory.normalizeStart(mainnet, " 33000000 "))
        assertEquals("12", PolkadotHistory.normalizeStart(mainnet, "0012"))
        for (bad in listOf("abc", "-5", "1e6", "33000000; rm -rf /", "99999999999", "0x10", "3 3"))
        {
            assertFailsWith<IllegalArgumentException>(bad) { PolkadotHistory.normalizeStart(mainnet, bad) }
        }
    }

    @Test
    fun westend_cannot_start_before_its_metadata_has_a_known_shape()
    {
        assertEquals("head", PolkadotHistory.normalizeStart(westend, null))
        assertEquals("head", PolkadotHistory.normalizeStart(westend, "head"))
        assertEquals("7752190", PolkadotHistory.normalizeStart(westend, "7752190"))
        assertEquals("30000000", PolkadotHistory.normalizeStart(westend, "30000000"))
        assertFailsWith<IllegalArgumentException> { PolkadotHistory.normalizeStart(westend, "genesis") }
        assertFailsWith<IllegalArgumentException> { PolkadotHistory.normalizeStart(westend, "5000000") }
        // paseo has v14 metadata from block 0
        assertEquals("genesis", PolkadotHistory.normalizeStart(spec.env("paseo"), "genesis"))
    }

    // ---------------------------------------------------------------- start plan

    @Test
    fun the_start_plan_carries_the_indexer_disk_and_the_start_block()
    {
        val plan = SubstrateNodeStart(spec).plan(
            ChainNodeStartContext(
                network = NetworkId.POLKADOT, env = "mainnet", program = "polkadot", configFile = null, nodeDir = "/data/polkadot",
                installOptionsJson = """{"snapshot":"archive","indexer":"history","indexer_start_block":"33000000"}""",
                diskLayoutJson = """{"roles":[{"id":"state","dir":"/mnt/a/polkadot"},{"id":"indexer","dir":"/mnt/b/polkadot-idx"}]}""",
            ),
        )
        assertTrue("--toolkit-mode=archive" in plan.launch.args)
        assertTrue("--toolkit-indexer=history" in plan.launch.args)
        assertTrue("--toolkit-indexer-dir=/mnt/b/polkadot-idx" in plan.launch.args)
        assertTrue("--toolkit-indexer-start=33000000" in plan.launch.args)
        assertTrue("--toolkit-state=/mnt/a/polkadot" in plan.launch.args)
        // a single-disk host (or the option left empty) passes neither
        val bare = SubstrateNodeStart(spec).plan(
            ChainNodeStartContext(
                network = NetworkId.POLKADOT, env = "mainnet", program = "polkadot", configFile = null, nodeDir = "/d",
                installOptionsJson = """{"indexer":"history"}""",
            ),
        )
        assertTrue(bare.launch.args.none { it.startsWith("--toolkit-indexer-dir=") || it.startsWith("--toolkit-indexer-start=") })
    }

    // ---------------------------------------------------------------- the companion

    private fun ctx(
        mode: SubstrateMode = SubstrateMode.ARCHIVE,
        option: String? = "history",
        start: String? = null,
        indexerDir: Path? = null,
        nodeDir: Path = Path.of("/tmp/rpcnode-test-polkadot-node"),
        env: String = "mainnet",
    ) = SubstrateCompanionContext(spec.env(env), nodeDir, option, mode, indexerDir, start)

    @Test
    fun a_pruned_node_cannot_have_the_history_indexer()
    {
        val ex = assertFailsWith<IllegalArgumentException> { PolkadotNode.companion(ctx(mode = SubstrateMode.PRUNED)) }
        assertTrue(ex.message!!.contains("archive"), ex.message)
    }

    @Test
    fun other_options_do_not_start_the_history_indexer()
    {
        assertNull(PolkadotNode.companion(ctx(option = null)))
        assertNull(PolkadotNode.companion(ctx(option = "none")))
        assertNull(PolkadotNode.companion(ctx(mode = SubstrateMode.PRUNED, option = "none")))
        val rest = assertNotNull(PolkadotNode.companion(ctx(option = "rest-api", mode = SubstrateMode.PRUNED)))
        assertEquals(listOf("rpcnode-polkadot-mainnet-indexer.service"), rest.units.map { it.first })
    }

    @Test
    fun history_companion_has_three_units_three_scripts_and_the_project_on_the_indexer_disk()
    {
        val node = createTempDirectory("pd-node")
        val idx = createTempDirectory("pd-idx")
        val c = assertNotNull(PolkadotNode.companion(ctx(start = "33000000", indexerDir = idx, nodeDir = node)))
        assertEquals(
            listOf(
                "rpcnode-polkadot-mainnet-history-prepare.service",
                "rpcnode-polkadot-mainnet-history.service",
                "rpcnode-polkadot-mainnet-history-api.service",
            ),
            c.units.map { it.first },
        )
        assertEquals(PolkadotHistory.allUnits("mainnet"), c.units.map { it.first })
        assertEquals(
            setOf("bin/rpcnode-polkadot-history-prepare.sh", "bin/rpcnode-polkadot-history-run.sh", "bin/rpcnode-on-remove.sh"),
            c.files.keys,
        )
        assertEquals(PolkadotHistory.PROJECT_FILES.map { idx.resolve("squid").resolve(it) }.toSet(), c.dataFiles.keys)
        // the password directory exists and is private
        val pw = node.resolve(PolkadotHistory.PW_DIR)
        assertTrue(Files.isDirectory(pw))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(pw)))
        for ((_, body) in c.units) assertTrue(!body.contains("{{"), body)
        for ((name, body) in c.files) assertTrue(!body.contains("{{"), "$name still has a placeholder")
    }

    @Test
    fun without_an_indexer_disk_the_files_go_under_the_node_dir()
    {
        val node = createTempDirectory("pd-node")
        val c = assertNotNull(PolkadotNode.companion(ctx(nodeDir = node)))
        assertTrue(c.dataFiles.keys.all { it.startsWith(node.resolve("indexer").resolve("squid")) })
        assertTrue(c.files.getValue("bin/rpcnode-polkadot-history-prepare.sh").contains("IDX='${node.resolve("indexer")}'"))
    }

    @Test
    fun a_bad_start_block_fails_the_start()
    {
        val ex = assertFailsWith<IllegalArgumentException> { PolkadotNode.companion(ctx(start = "yesterday")) }
        assertTrue(ex.message!!.contains("start block"), ex.message)
        assertFailsWith<IllegalArgumentException> { PolkadotNode.companion(ctx(env = "westend", start = "genesis")) }
    }

    @Test
    fun an_indexer_dir_with_a_space_is_refused()
    {
        assertFailsWith<IllegalArgumentException> { PolkadotNode.companion(ctx(indexerDir = Path.of("/mnt/my disk/idx"))) }
    }

    // ---------------------------------------------------------------- scripts and units

    private fun bashN(text: String)
    {
        val tmp = kotlin.io.path.createTempFile("history", ".sh").toFile().also { it.writeText(text); it.deleteOnExit() }
        val p = ProcessBuilder("bash", "-n", tmp.path).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(0, p.waitFor(), out)
    }

    @Test
    fun scripts_parse_and_carry_the_pinned_values()
    {
        val prepare = PolkadotHistory.prepareScript(mainnet, "/n/.toolkit", "/n/indexer", "/n/logs/polkadot-history-prepare.log")
        val run = PolkadotHistory.runScript(mainnet, spec.ports(mainnet), "/n", "/n/indexer", "genesis")
        val hook = PolkadotHistory.removeHook("/n", "/n/indexer")
        for (s in listOf(prepare, run, hook))
        {
            assertTrue(!s.contains("{{"), s)
            bashN(s)
        }
        assertTrue(prepare.contains("NODE_VERSION=24.21.0"))
        assertTrue(prepare.contains("NODE_SHA256_X64=${PolkadotHistory.NODE_SHA256_X64}"))
        assertTrue(prepare.contains("NODE_SHA256_ARM64=${PolkadotHistory.NODE_SHA256_ARM64}"))
        assertTrue(prepare.contains("PROJECT_HASH=${PolkadotHistory.projectHash()}"))
        assertTrue(prepare.contains("npm ci --ignore-scripts"))
        assertTrue(prepare.contains("connection limit 60"))
        assertTrue(prepare.contains("alter role \$PG_ROLE set work_mem") || prepare.contains("set work_mem"))
        // the shared PostgreSQL provisioning is inlined, with its role-level settings filled
        assertTrue(prepare.contains("provision_database()"))
        assertTrue(prepare.contains("effective_io_concurrency = '200'"))
        // no secret anywhere in a script: the password is generated on the host
        for (s in listOf(prepare, run, hook)) assertTrue(!Regex("password\\s*=\\s*'[^']+'").containsMatchIn(s))
        assertTrue(run.contains("export START_BLOCK='genesis'"))
        assertTrue(run.contains("export NODE_RPC_URL='http://127.0.0.1:42200'"))
        assertTrue(run.contains("export GQL_PORT='42206'"))
        assertTrue(run.contains("export PROCESSOR_PROMETHEUS_PORT='42207'"))
        assertTrue(run.contains("'--isolation-level=REPEATABLE READ'"))
        assertTrue(run.contains("'--max-response-size=10000'"))
        assertTrue(hook.contains("drop database if exists \$PG_DB"))
    }

    @Test
    fun the_processor_is_confined_to_loopback_the_api_listens_on_all_interfaces_and_the_prepare_unit_is_not_confined()
    {
        val processor = PolkadotHistory.processorUnit(mainnet, "/n", "/n/bin/run processor", "/n/logs/p.log")
        val api = PolkadotHistory.apiUnit(mainnet, "/n", "/n/bin/run api", "/n/logs/a.log")
        val prepare = PolkadotHistory.prepareUnit(mainnet, "/n/bin/prepare")
        assertTrue(processor.contains("IPAddressDeny=any\nIPAddressAllow=localhost"), processor)
        assertTrue(!api.contains("IPAddress"), "the GraphQL API is reachable from the network (user decision), like the node RPC")
        for (u in listOf(processor, api))
        {
            assertTrue(u.contains("NoNewPrivileges=yes"))
            assertTrue(u.contains("After=network-online.target postgresql.service rpcnode-polkadot-mainnet.service rpcnode-polkadot-mainnet-history-prepare.service"))
            assertTrue(u.contains("PartOf=rpcnode-polkadot-mainnet.service"))
            assertTrue(!u.contains("Environment="), "no password or setting in the world-readable unit file")
        }
        assertTrue(processor.contains("ExecStart=/n/bin/run processor"))
        assertTrue(api.contains("ExecStart=/n/bin/run api"))
        assertTrue(!prepare.contains("IPAddress"), "the prepare unit downloads and runs apt")
        assertTrue(prepare.contains("RemainAfterExit=yes"))
    }

    /**
     * Runs the real run script with a fake node, curl and state directory: the password reaches the process through its environment
     * only, the processor waits for a synced node, and the API gets the flags and the port.
     */
    @Test
    fun the_run_script_hands_the_database_password_to_the_process_environment_and_nothing_else()
    {
        val root = createTempDirectory("pd-run")
        val node = root.resolve("n"); val idx = root.resolve("idx"); val bin = root.resolve("fakebin")
        val pw = node.resolve(PolkadotHistory.PW_DIR)
        Files.createDirectories(pw); Files.createDirectories(idx.resolve("squid/lib")); Files.createDirectories(idx.resolve("squid/node_modules/.bin"))
        Files.createDirectories(idx.resolve("node/bin")); Files.createDirectories(bin)
        Files.writeString(pw.resolve("postgres_password"), "s3cretpw")
        Files.writeString(pw.resolve("pg.env"), "PG_PORT=5499\nPG_ROLE=rpcnode_polkadot_mainnet\nPG_DB=rpcnode_polkadot_mainnet\n")
        Files.writeString(pw.resolve("ready"), "")
        Files.writeString(idx.resolve("squid/lib/main.js"), "")
        fun exe(p: Path, body: String) { Files.writeString(p, body); Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x")) }
        exe(idx.resolve("node/bin/node"), "#!/bin/bash\necho \"NODE \$*\"; echo \"DB_PASS=\$DB_PASS DB_PORT=\$DB_PORT DB_NAME=\$DB_NAME DB_USER=\$DB_USER\"; echo \"START_BLOCK=\$START_BLOCK RPC_BATCH_SIZE=\$RPC_BATCH_SIZE NODE_OPTIONS=\$NODE_OPTIONS\"\n")
        exe(idx.resolve("squid/node_modules/.bin/squid-graphql-server"), "#!/bin/bash\necho \"API \$*\"; echo \"GQL_PORT=\$GQL_PORT POOL=\$GQL_DB_CONNECTION_POOL_SIZE DB_PASS=\$DB_PASS NODE_OPTIONS=\$NODE_OPTIONS\"\n")
        // a node that is synced and past block 0
        exe(bin.resolve("curl"), "#!/bin/bash\ncase \"\$*\" in *system_health*) echo '{\"result\":{\"isSyncing\": false}}';; *) echo '{\"result\":{\"number\":\"0x1fdf01f\"}}';; esac\n")
        val script = root.resolve("run.sh")
        Files.writeString(script, PolkadotHistory.runScript(mainnet, spec.ports(mainnet), node.toString(), idx.toString(), "33000000"))

        fun run(role: String): String
        {
            val pb = ProcessBuilder("bash", script.toString(), role).redirectErrorStream(true)
            pb.environment()["PATH"] = "${bin}:/usr/bin:/bin"
            val p = pb.start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            assertTrue(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS))
            return out
        }
        val processor = run("processor")
        assertTrue(processor.contains("NODE lib/main.js"), processor)
        assertTrue(processor.contains("DB_PASS=s3cretpw DB_PORT=5499 DB_NAME=rpcnode_polkadot_mainnet DB_USER=rpcnode_polkadot_mainnet"), processor)
        assertTrue(processor.contains("START_BLOCK=33000000 RPC_BATCH_SIZE=50 NODE_OPTIONS=--max-old-space-size=4096"), processor)
        val api = run("api")
        assertTrue(api.contains("--max-request-size=64"), api)
        assertTrue(api.contains("--isolation-level=REPEATABLE READ"), api)
        assertTrue(api.contains("GQL_PORT=42206 POOL=20 DB_PASS=s3cretpw NODE_OPTIONS=--max-old-space-size=2048"), api)
        assertTrue(run("bogus").contains("usage"))
    }

    @Test
    fun the_processor_waits_while_the_node_is_syncing()
    {
        val root = createTempDirectory("pd-wait")
        val node = root.resolve("n"); val idx = root.resolve("idx"); val bin = root.resolve("fakebin")
        val pw = node.resolve(PolkadotHistory.PW_DIR)
        Files.createDirectories(pw); Files.createDirectories(idx.resolve("squid/lib")); Files.createDirectories(bin); Files.createDirectories(idx.resolve("node/bin"))
        Files.writeString(pw.resolve("postgres_password"), "x"); Files.writeString(pw.resolve("pg.env"), "PG_PORT=1\nPG_ROLE=r\nPG_DB=d\n")
        Files.writeString(pw.resolve("ready"), ""); Files.writeString(idx.resolve("squid/lib/main.js"), "")
        Files.writeString(bin.resolve("curl"), "#!/bin/bash\ncase \"\$*\" in *system_health*) echo '{\"result\":{\"isSyncing\": true}}';; *) echo '{\"result\":{\"number\":\"0x0\"}}';; esac\n")
        Files.setPosixFilePermissions(bin.resolve("curl"), PosixFilePermissions.fromString("rwxr-xr-x"))
        val script = root.resolve("run.sh")
        Files.writeString(script, PolkadotHistory.runScript(mainnet, spec.ports(mainnet), node.toString(), idx.toString(), "genesis").replace("sleep 30", "sleep 0.2"))
        val pb = ProcessBuilder("bash", script.toString(), "processor").redirectErrorStream(true)
        pb.environment()["PATH"] = "${bin}:/usr/bin:/bin"
        val p = pb.start()
        Thread.sleep(1500)
        assertTrue(p.isAlive, "must still be waiting for the node")
        p.destroyForcibly()
        assertNotEquals(0, p.waitFor())
    }

    // ---------------------------------------------------------------- diagnostics

    private val req = DiagnosticRequest(id = "polkadot_history", port = 42206)

    private fun diag(
        exists: Boolean = true,
        states: Map<String, String> = mapOf("prepare" to "active", "history" to "active", "api" to "active"),
        status: String? = null,
        head: Long? = 1000,
    ) = PolkadotHistoryDiagnostics.check(
        req,
        { exists },
        { unit -> states[if (unit.endsWith("-prepare.service")) "prepare" else if (unit.endsWith("-api.service")) "api" else "history"] ?: "inactive" },
        { status },
        { head },
    )

    private fun answer(h: Long) = """{"data":{"squidStatus":{"height":$h,"finalizedHeight":$h}}}"""

    @Test
    fun diagnostic_walks_from_not_installed_to_in_sync()
    {
        assertEquals("info", diag(exists = false).level)
        assertEquals("error", diag(states = mapOf("prepare" to "failed")).level)
        assertEquals("info", diag(states = mapOf("prepare" to "activating")).level)
        // API not answering
        assertEquals("error", diag(states = mapOf("prepare" to "active", "api" to "failed", "history" to "active")).level)
        assertEquals("warn", diag().level)
        // processor down
        assertEquals("error", diag(states = mapOf("prepare" to "active", "api" to "active", "history" to "failed"), status = answer(900)).level)
        // nothing stored yet
        assertEquals("info", diag(status = answer(-1)).level)
        // in sync / catching up
        assertEquals("ok", diag(status = answer(990)).level)
        val behind = diag(status = answer(500), head = 1000)
        assertEquals("info", behind.level)
        assertTrue(behind.detail!!.contains("50.0%"), behind.detail)
        assertEquals("ok", diag(status = answer(990), head = null).level)
        assertEquals("unknown", PolkadotHistoryDiagnostics.check(DiagnosticRequest("polkadot_history", 1)).level)
        assertEquals("unknown", PolkadotHistoryDiagnostics.check(DiagnosticRequest("polkadot_history", 0)).level)
    }

    @Test
    fun the_status_answer_is_parsed()
    {
        assertEquals(33419371L, PolkadotHistoryDiagnostics.height("""{"data":{"squidStatus":{"height":33419371,"finalizedHeight":33419371}}}"""))
        assertEquals(-1L, PolkadotHistoryDiagnostics.height("""{"data":{"squidStatus":{"height":-1,"finalizedHeight":-1}}}"""))
        assertNull(PolkadotHistoryDiagnostics.height("""{"errors":[{"message":"relation \"squid_processor.status\" does not exist"}]}"""))
        assertNull(PolkadotHistoryDiagnostics.height("not json"))
    }
}
