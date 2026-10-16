package rpcnode.toolkit.chains.opstack.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpstackUnitBodiesTest
{
    private val layout = OpstackUnitBodies.Layout(
        nodeDir = "/data/n",
        execDir = "/data/n/exec",
        consDir = "/data/n/cons",
        snapDir = "/data/n/snaps",
        stateDir = "/data/n/.toolkit",
        logFile = "/data/n/logs/x.log",
    )

    private fun script(
        spec: OpstackNetworkSpec,
        env: String = "mainnet",
        mode: String = "full",
        restore: String = "snapshot",
        url: String = "",
    ) = OpstackUnitBodies.runScript(
        spec, env, OpstackInstallOptions(node = mode, restore = restore, snapshotUrl = url), layout, "https://l1.example", "https://beacon.example",
    )

    private fun bashSyntaxOk(body: String)
    {
        val file = Files.createTempFile("opstack-run", ".sh")
        try
        {
            Files.writeString(file, body)
            val p = ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start()
            assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
        }
        finally
        {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun every_network_env_and_mode_renders_valid_bash_without_placeholders()
    {
        for (spec in OpstackNetworks.ALL) for (env in spec.envs) for (mode in listOf("full", "archive"))
        {
            val body = script(spec, env.id, mode)
            assertFalse(body.contains("{{"), "${spec.network.value}/${env.id}")
            assertTrue(body.contains("RETH_CHAIN='${env.rethChain}'"))
            assertTrue(body.contains("NODE_NETWORK='${env.nodeNetwork}'"))
            assertTrue(body.contains("SEQUENCER='${env.sequencer}'"))
            assertTrue(body.contains("SYNC_MODE='${env.syncMode.flag}'"))
            assertTrue(body.contains("HTTP_PORT=${env.ports.http}"))
            assertTrue(body.contains("V5_PORT=${env.ports.discv5}"))
            assertTrue(body.contains("PRUNE_DISTANCE=${env.pruneDistance}"))
            assertTrue(body.contains(":-${OpstackToolchain.RETH_TAG}}"))
            assertTrue(body.contains(":-${OpstackToolchain.NODE_TAG}}"))
            bashSyntaxOk(body)
        }
    }

    @Test
    fun the_prune_window_is_thirty_days_of_blocks()
    {
        assertEquals(2_592_000, OpstackNetworks.UNICHAIN.env("mainnet").pruneDistance, "1 s blocks")
        assertEquals(2_592_000, OpstackNetworks.INK.env("sepolia").pruneDistance)
        assertEquals(1_296_000, OpstackNetworks.SONEIUM.env("mainnet").pruneDistance, "2 s blocks")
        assertEquals(1_296_000, OpstackNetworks.LISK.env("mainnet").pruneDistance)
    }

    @Test
    fun the_discv5_port_and_ipc_collision_with_other_op_reth_nodes_is_fixed()
    {
        val body = script(OpstackNetworks.UNICHAIN)
        assertTrue(body.contains("--ipcdisable"), "no shared /tmp/reth.ipc")
        assertTrue(body.contains("--discovery.v5.port=\"\$V5_PORT\""), "no shared 9200/udp")
        assertTrue(body.contains("--discovery.port=\"\$P2P_PORT\""))
        // two networks never render the same discv5 port
        val ports = OpstackNetworks.ALL.flatMap { s -> s.envs.map { it.ports.discv5 } }
        assertEquals(ports.size, ports.toSet().size)
        assertFalse(ports.contains(9200))
    }

    @Test
    fun only_the_public_rpc_listens_on_all_interfaces()
    {
        val body = script(OpstackNetworks.INK)
        assertTrue(body.contains("--http.addr=\"\$HTTP_ADDR\""))
        assertTrue(body.contains("HTTP_ADDR='0.0.0.0'"))
        assertTrue(body.contains("LOOPBACK='127.0.0.1'"))
        assertTrue(body.contains("--authrpc.addr=\"\$LOOPBACK\""), "the JWT-guarded Engine API stays local")
        assertTrue(body.contains("--metrics=\"\$LOOPBACK:\$METRICS_PORT\""))
        assertTrue(body.contains("--rpc.addr=\"\$LOOPBACK\""), "op-node RPC")
        assertTrue(body.contains("--metrics.addr=\"\$LOOPBACK\""))
        assertFalse(body.contains("0.0.0.0:"), "no 0.0.0.0 metrics bind")
        assertFalse(body.contains("--rpc.addr=0.0.0.0"))
        assertTrue(body.contains("HTTP_API='eth,net,web3'"))
        assertFalse(body.contains("debug") || body.contains("txpool,") || body.contains(",trace"), "no debug/trace/txpool namespace")
    }

    @Test
    fun archive_never_prunes_and_full_prunes_state_but_not_block_bodies()
    {
        val body = script(OpstackNetworks.SONEIUM, mode = "archive")
        assertTrue(body.contains("MODE='archive'"))
        assertTrue(body.contains("""if [[ "${'$'}MODE" != archive ]]; then"""))
        assertTrue(body.contains("--prune.receipts.distance"))
        assertTrue(body.contains("--prune.account-history.distance"))
        assertTrue(body.contains("--prune.storage-history.distance"))
        assertFalse(body.contains("--prune.bodies"), "block bodies are never pruned: op-node reads them")
        assertFalse(body.contains("--minimal"), "op-reth rejects --minimal")
        assertTrue(body.contains("--l2.enginekind=reth"))
        assertTrue(body.contains("--verifier.l1-confs=\"\$L1_CONFS\""))
        assertTrue(body.contains("L1_CONFS=4"))
        assertTrue(body.contains("--rollup.disable-tx-pool-gossip"))
    }

    @Test
    fun sync_mode_per_network_follows_the_research()
    {
        assertTrue(script(OpstackNetworks.UNICHAIN).contains("SYNC_MODE='execution-layer'"))
        assertTrue(script(OpstackNetworks.WORLDCHAIN).contains("SYNC_MODE='execution-layer'"))
        for (spec in listOf(OpstackNetworks.INK, OpstackNetworks.SONEIUM, OpstackNetworks.LISK, OpstackNetworks.MODE, OpstackNetworks.ZORA))
        {
            assertTrue(script(spec).contains("SYNC_MODE='consensus-layer'"), spec.network.value)
        }
    }

    @Test
    fun peers_are_only_passed_where_the_operator_publishes_them()
    {
        val ink = script(OpstackNetworks.INK)
        assertTrue(ink.contains("CL_STATIC='/ip4/34.6.118.237/tcp/9003/p2p/16Uiu2HAmKjbznKezrhsXMXVT3tSwviv8jojmfLeY9Zd53VvLmGsn,"))
        assertTrue(ink.contains("CL_BOOTNODES='enode://92534205"))
        assertTrue(ink.contains("--p2p.static=\"\$CL_STATIC\""))
        assertTrue(ink.contains("--p2p.bootnodes=\"\$CL_BOOTNODES\""))
        val lisk = script(OpstackNetworks.LISK)
        assertTrue(lisk.contains("CL_BOOTNODES='enr:-Iu4QBCIUw0"))
        assertTrue(lisk.contains("EL_TRUSTED=''"))
        val mode = script(OpstackNetworks.MODE)
        assertTrue(mode.contains("EL_TRUSTED='enode://0bda30d2"))
        assertTrue(mode.contains("CL_STATIC='/ip4/136.118.47.173/tcp/9222/p2p/16Uiu2HAmMKH"))
        assertTrue(script(OpstackNetworks.MODE, "sepolia").contains("CL_STATIC=''"))
        // op-reth ships OP Stack bootnodes for every registry chain, so Unichain passes none
        assertTrue(script(OpstackNetworks.UNICHAIN).contains("CL_STATIC=''"))
    }

    @Test
    fun world_chain_runs_the_release_binary_with_flashblocks_on_mainnet_only()
    {
        val main = script(OpstackNetworks.WORLDCHAIN)
        assertTrue(main.contains("EL_FLAVOR='world-chain'"))
        assertTrue(main.contains("EL_BIN_NAME='world-chain'"))
        assertTrue(main.contains("EL_EXTRA='--flashblocks.enabled'"))
        assertTrue(main.contains("use_release_tar()"))
        assertTrue(main.contains("\$ROOT/\$1-\$(uname -m).tar.gz"), "the tarball name matches clients.yml artifact names")
        assertTrue(script(OpstackNetworks.WORLDCHAIN, "sepolia").contains("EL_EXTRA=''"))
        assertTrue(script(OpstackNetworks.UNICHAIN).contains("EL_BIN_NAME='op-reth'"))
    }

    @Test
    fun snapshot_sources_per_network()
    {
        val unichain = script(OpstackNetworks.UNICHAIN)
        assertTrue(unichain.contains("SNAP_KIND='alchemy'") && unichain.contains("SNAP_REF='unichain'") && unichain.contains("SNAP_PATH='unichain/main'"))
        val unichainSep = script(OpstackNetworks.UNICHAIN, "sepolia")
        assertTrue(unichainSep.contains("SNAP_REF='unichain-testnet'") && unichainSep.contains("SNAP_PATH='unichain/sep'"))
        assertTrue(script(OpstackNetworks.INK, "sepolia").contains("SNAP_PATH='ink/sep'"))
        assertTrue(script(OpstackNetworks.SONEIUM).contains("SNAP_PATH='soneium/main'"))
        assertTrue(script(OpstackNetworks.SONEIUM, "sepolia").contains("SNAP_KIND='none'"), "Minato has no snapshot")
        val lisk = script(OpstackNetworks.LISK)
        assertTrue(lisk.contains("SNAP_KIND='gelato'") && lisk.contains("SNAP_REF='lisk.snapshots.gelato.cloud'"))
        assertTrue(lisk.contains("""[[ "${'$'}shaurl" != "-" ]]"""), "gelato snapshots are checksum-verified")
        val world = script(OpstackNetworks.WORLDCHAIN)
        assertTrue(world.contains("SNAP_KIND='fixed'"))
        assertTrue(world.contains("SNAP_URL_FULL='https://world-chain-snapshots.s3.eu-central-2.amazonaws.com/reth_full.tar.lz4'"))
        assertTrue(world.contains("SNAP_URL_ARCHIVE='https://world-chain-snapshots.s3.eu-central-2.amazonaws.com/reth_archive.tar.lz4'"))
        assertTrue(script(OpstackNetworks.WORLDCHAIN, "sepolia").contains("world-chain-testnet-snapshots.s3"))
        for (spec in listOf(OpstackNetworks.MODE, OpstackNetworks.ZORA))
        {
            for (env in spec.envs) assertTrue(script(spec, env.id).contains("SNAP_KIND='none'"), "${spec.network.value}/${env.id}")
        }
    }

    @Test
    fun the_snapshot_choice_and_the_operator_url_reach_the_script()
    {
        assertTrue(script(OpstackNetworks.UNICHAIN).contains("SNAP_WANTED=1"))
        assertTrue(script(OpstackNetworks.UNICHAIN, restore = "none").contains("SNAP_WANTED=0"))
        val custom = script(OpstackNetworks.MODE, url = "https://files.example/mode-datadir.tar.lz4")
        assertTrue(custom.contains("SNAP_CUSTOM='https://files.example/mode-datadir.tar.lz4'"))
        assertTrue(custom.contains("SNAP_WANTED=1"))
        // none wins over a URL: an explicit "no snapshot" is not overridden
        assertTrue(script(OpstackNetworks.MODE, restore = "none", url = "https://files.example/a.tar.gz").contains("SNAP_WANTED=0"))
        // a single quote in the URL cannot break out of the assignment
        val hostile = OpstackUnitBodies.q("https://x/a'b.tar.gz")
        assertEquals("'https://x/a'\\''b.tar.gz'", hostile)
    }

    @Test
    fun a_restore_never_clobbers_an_existing_database_and_does_not_reuse_the_providers_node_identity()
    {
        val body = script(OpstackNetworks.UNICHAIN)
        assertTrue(body.contains("already holds a database — not restoring"))
        assertTrue(body.contains("""rm -f "${'$'}EXEC/discovery-secret" "${'$'}EXEC/jwt.hex""""))
        assertTrue(body.contains("snapshot-\$kind.inprogress"), "an interrupted extraction is redone, not mistaken for a finished one")
    }

    @Test
    fun streams_the_restore_only_when_disk_cannot_hold_archive_plus_datadir()
    {
        val body = script(OpstackNetworks.WORLDCHAIN)
        assertTrue(body.contains("streaming the restore (not resumable)"))
        assertTrue(body.contains("--checksum=\"sha-256=\$want\""))
        assertTrue(body.contains("-r 0-4194303"), "wrapper dir of the archive is detected from its first MiB when streaming")
    }

    @Test
    fun no_docker_in_the_install_path()
    {
        val body = script(OpstackNetworks.UNICHAIN)
        assertFalse(body.contains("docker", ignoreCase = true))
        assertTrue(body.contains("cargo build --release --bin op-reth -p op-reth"))
        assertTrue(body.contains("./op-node/cmd"))
        assertTrue(body.contains("sync-superchain.sh"), "op-node needs superchain-configs.zip generated before go build")
        assertTrue(body.contains("does not run on this host — building from source"))
    }

    @Test
    fun unit_renders_per_network()
    {
        for (spec in OpstackNetworks.ALL)
        {
            val unit = OpstackUnitBodies.unit(spec, "mainnet", "/n", "/n/${spec.runScript}", "/n/${spec.relativeLog}")
            assertFalse(unit.contains("{{"), spec.network.value)
            assertTrue(unit.contains("ExecStart=/n/bin/rpcnode-${spec.network.value}-run.sh"))
            assertTrue(unit.contains("LimitNOFILE=${OpstackUnitBodies.NODE_NOFILE}"))
            assertTrue(unit.contains("Description=${spec.label} ${spec.elFlavor.binary} + op-node"))
            // each network ships its own template next to clients.yml and it equals the shared shape
            assertTrue(javaClass.classLoader.getResource("chains/${spec.network.value}/node.service.tmpl") != null)
        }
    }

    // --- the pure-bash helpers of the template, run against real provider pages ---------------------------------------------

    private fun bashOk(): Boolean = runCatching {
        ProcessBuilder("bash", "-c", "(( BASH_VERSINFO[0] > 4 || (BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] >= 3) ))").start().waitFor() == 0
    }.getOrDefault(false)

    /** Source `resolve_snapshot` out of the rendered script and run it with `curl` replaced by [page]. */
    private fun resolve(spec: OpstackNetworkSpec, env: String, mode: String, page: String, custom: String = ""): String
    {
        val body = script(spec, env, mode, url = custom)
        val dir = Files.createTempDirectory("opstack-resolve")
        val pageFile = dir.resolve("page.html")
        Files.writeString(pageFile, page)
        val from = body.indexOf("resolve_snapshot() {")
        val to = body.indexOf("\n}\n", from) + 3
        val header = body.lineSequence().filter { Regex("^(SNAP_(KIND|REF|PATH|URL_FULL|URL_ARCHIVE|CUSTOM)|NET)=").containsMatchIn(it) }.joinToString("\n")
        val program = """
            set -u
            $header
            kind=full; [[ '$mode' == archive ]] && kind=archive
            curl() { cat '$pageFile'; }
            ${body.substring(from, to)}
            resolve_snapshot
        """.trimIndent()
        val p = ProcessBuilder("bash", "-c", program).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out
    }

    private fun stripFor(first: String): String
    {
        val body = script(OpstackNetworks.UNICHAIN)
        val from = body.indexOf("strip_for() {")
        val to = body.indexOf("\n}\n", from) + 3
        val p = ProcessBuilder("bash", "-c", "${body.substring(from, to)}\nstrip_for '$first'").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out
    }

    @Test
    fun alchemy_page_scrape_picks_the_reth_link_of_the_right_chain_and_ignores_the_geth_one()
    {
        if (!bashOk()) return
        // the real page of 2026-10-13 embeds both clients; testnet pages embed their own path
        val page = """
            <a href="https://pub-c5ad1adfc7a74a99a19b1c7f3d621fa8.r2.dev/rpc-platform/ink/main/gt/gt-20260401222957.tar.gz">geth</a>
            <a href="https://pub-c5ad1adfc7a74a99a19b1c7f3d621fa8.r2.dev/rpc-platform/ink/main/reth/reth-20261011100001.tar.gz">old</a>
            <a href="https://pub-c5ad1adfc7a74a99a19b1c7f3d621fa8.r2.dev/rpc-platform/ink/main/reth/reth-20261012100001.tar.gz">reth</a>
            <a href="https://pub-c5ad1adfc7a74a99a19b1c7f3d621fa8.r2.dev/rpc-platform/ink/sep/reth/reth-20261012220001.tar.gz">sep</a>
        """.trimIndent()
        assertEquals(
            "https://pub-c5ad1adfc7a74a99a19b1c7f3d621fa8.r2.dev/rpc-platform/ink/main/reth/reth-20261012100001.tar.gz -",
            resolve(OpstackNetworks.INK, "mainnet", "full", page),
        )
        assertEquals(
            "https://pub-c5ad1adfc7a74a99a19b1c7f3d621fa8.r2.dev/rpc-platform/ink/sep/reth/reth-20261012220001.tar.gz -",
            resolve(OpstackNetworks.INK, "sepolia", "archive", page),
        )
        assertEquals("", resolve(OpstackNetworks.UNICHAIN, "mainnet", "full", page), "no unichain link on that page")
    }

    @Test
    fun gelato_index_picks_the_newest_full_or_archival_datadir_by_epoch_with_its_checksum()
    {
        if (!bashOk()) return
        val host = "https://lisk.snapshots.gelato.cloud/reth"
        val page = """
            href="$host/archival/datadir/lisk-reth-archival-datadir-38388677-1791506181.tar.lz4"
            href="$host/archival/datadir/lisk-reth-archival-datadir-38388677-1791506181.tar.lz4.sha256"
            href="$host/full/datadir/lisk-reth-full-datadir-38054843-1790838513.tar.lz4"
            href="$host/full/datadir/lisk-reth-full-datadir-38357187-1791443200.tar.lz4"
            href="$host/full/datadir/lisk-reth-full-datadir-38357187-1791443200.tar.lz4.sha256"
            href="$host/full/datadir/lisk-reth-full-datadir-37363626-1789456079.tar.lz4"
        """.trimIndent()
        val full = "$host/full/datadir/lisk-reth-full-datadir-38357187-1791443200.tar.lz4"
        assertEquals("$full $full.sha256", resolve(OpstackNetworks.LISK, "mainnet", "full", page))
        val arch = "$host/archival/datadir/lisk-reth-archival-datadir-38388677-1791506181.tar.lz4"
        assertEquals("$arch $arch.sha256", resolve(OpstackNetworks.LISK, "mainnet", "archive", page))
    }

    @Test
    fun fixed_and_custom_sources_resolve_without_any_page()
    {
        if (!bashOk()) return
        assertEquals(
            "https://world-chain-snapshots.s3.eu-central-2.amazonaws.com/reth_full.tar.lz4 -",
            resolve(OpstackNetworks.WORLDCHAIN, "mainnet", "full", ""),
        )
        assertEquals(
            "https://world-chain-testnet-snapshots.s3.eu-central-2.amazonaws.com/reth_archive.tar.lz4 -",
            resolve(OpstackNetworks.WORLDCHAIN, "sepolia", "archive", ""),
        )
        assertEquals(
            "https://files.example/z.tar.zst -",
            resolve(OpstackNetworks.ZORA, "mainnet", "full", "", custom = "https://files.example/z.tar.zst"),
        )
        assertEquals("", resolve(OpstackNetworks.ZORA, "mainnet", "full", ""), "no source and no URL: nothing to restore")
    }

    @Test
    fun the_wrapper_directory_is_stripped_only_when_the_archive_has_one()
    {
        if (!bashOk()) return
        assertEquals("0", stripFor("./"), "Alchemy / Gelato archives start with ./")
        assertEquals("0", stripFor("./db/"))
        assertEquals("0", stripFor("db/mdbx.dat"))
        assertEquals("0", stripFor("static_files/"))
        assertEquals("1", stripFor("reth/"), "World Chain testnet archive: reth/known-peers.json …")
        assertEquals("1", stripFor("data/db/"))
        assertEquals("0", stripFor(""), "an unreadable listing falls back to no strip rather than eating the first level")
    }

    private fun sh(script: String): Pair<Int, String>
    {
        val p = ProcessBuilder("bash", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        return p.waitFor() to out
    }

    private fun toolExists(name: String) = sh("command -v $name").first == 0

    @Test
    fun archives_of_every_format_are_listed_and_extracted_with_or_without_a_wrapper_directory()
    {
        if (!bashOk()) return
        val body = script(OpstackNetworks.UNICHAIN)
        fun fn(name: String): String
        {
            val from = body.indexOf("$name() {")
            return body.substring(from, body.indexOf("\n}\n", from) + 3)
        }
        val helpers = listOf("list_first", "strip_for", "extract_to").joinToString("\n") { fn(it) }
        // the script runs on Debian/Ubuntu (GNU tar understands `-I <compressor>`); bsdtar on a dev Mac only takes gzip / plain
        val gnuTar = sh("tar --version").second.contains("GNU tar")
        val formats = listOf("tar.gz" to "gzip", "tar.zst" to "zstd", "tar.lz4" to "lz4", "tar" to "tar")
        for ((ext, tool) in formats)
        {
            if (!toolExists(tool) || (!gnuTar && ext in setOf("tar.zst", "tar.lz4"))) continue
            for (wrapped in listOf(false, true))
            {
                val work = Files.createTempDirectory("opstack-extract")
                val src = work.resolve("src")
                val dest = work.resolve("dest")
                Files.createDirectories(src.resolve(if (wrapped) "reth/db" else "db"))
                Files.createDirectories(dest)
                Files.writeString(src.resolve(if (wrapped) "reth/db/mdbx.dat" else "db/mdbx.dat"), "x")
                val top = if (wrapped) "reth" else "."
                val pack = when (ext)
                {
                    "tar.gz" -> "tar -czf"
                    "tar.zst" -> "tar -I zstd -cf"
                    "tar.lz4" -> "tar -I lz4 -cf"
                    else -> "tar -cf"
                }
                val archive = work.resolve("a.$ext")
                val prog = """
                    set -u; set -o pipefail
                    $helpers
                    $pack '$archive' -C '$src' $top
                    first="${'$'}(list_first '$archive' 'a.$ext')"
                    strip="${'$'}(strip_for "${'$'}first")"
                    extract_to '$archive' 'a.$ext' '$dest' "${'$'}strip" && echo "strip=${'$'}strip"
                """.trimIndent()
                val (code, out) = sh(prog)
                assertEquals(0, code, "$ext wrapped=$wrapped: $out")
                assertTrue(out.endsWith("strip=${if (wrapped) 1 else 0}"), "$ext wrapped=$wrapped: $out")
                assertTrue(Files.isRegularFile(dest.resolve("db/mdbx.dat")), "$ext wrapped=$wrapped: db/mdbx.dat not at the datadir root")
            }
        }
    }

    @Test
    fun the_script_file_name_and_log_are_per_network()
    {
        assertEquals("bin/rpcnode-unichain-run.sh", OpstackNetworks.UNICHAIN.runScript)
        assertEquals("logs/worldchain.log", OpstackNetworks.WORLDCHAIN.relativeLog)
        assertTrue(Path.of("src/main/resources/chains/opstack/run.sh.tmpl").toFile().isFile)
    }
}
