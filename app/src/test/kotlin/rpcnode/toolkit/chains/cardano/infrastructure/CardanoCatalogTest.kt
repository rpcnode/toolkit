package rpcnode.toolkit.chains.cardano.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.yaml.snakeyaml.Yaml

/** What the panel shows (network.yml bindings, clients.yml ports) must be what the node and its indexer companions run. */
class CardanoCatalogTest
{
    private fun resource(path: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing $path" }.readBytes().toString(Charsets.UTF_8)

    @Suppress("UNCHECKED_CAST")
    private fun yaml(path: String): Map<String, Any?> = Yaml().load<Any?>(resource(path)) as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private val bindings: List<Map<String, Any?>> =
        (yaml("chains/cardano/network.yml")["clientConfig"] as Map<String, Any?>)["bindings"] as List<Map<String, Any?>>

    private val literals: Map<String, String> =
        bindings.filter { it["source"] == "literal" }.associate { it["key"].toString() to it["value"].toString() }

    /** Every literal binding and the constant the unit / config / script applies. */
    private val applied: Map<String, String> = mapOf(
        "host-addr" to CardanoUnitBodies.HOST_ADDR,
        "PrometheusSimple.host" to CardanoUnitBodies.METRICS_HOST,
        "Stdout.format" to CardanoUnitBodies.STDOUT_FORMAT,
        "LedgerDB.Backend" to CardanoUnitBodies.LEDGER_BACKEND,
        "TargetNumberOfRootPeers" to CardanoUnitBodies.TARGET_ROOT.toString(),
        "TargetNumberOfKnownPeers" to CardanoUnitBodies.TARGET_KNOWN.toString(),
        "TargetNumberOfEstablishedPeers" to CardanoUnitBodies.TARGET_ESTABLISHED.toString(),
        "TargetNumberOfActivePeers" to CardanoUnitBodies.TARGET_ACTIVE.toString(),
        "TargetNumberOfKnownBigLedgerPeers" to CardanoUnitBodies.TARGET_BIG_KNOWN.toString(),
        "TargetNumberOfEstablishedBigLedgerPeers" to CardanoUnitBodies.TARGET_BIG_ESTABLISHED.toString(),
        "TargetNumberOfActiveBigLedgerPeers" to CardanoUnitBodies.TARGET_BIG_ACTIVE.toString(),
        "AcceptedConnectionsLimit.hardLimit" to CardanoUnitBodies.ACCEPT_HARD.toString(),
        "AcceptedConnectionsLimit.softLimit" to CardanoUnitBodies.ACCEPT_SOFT.toString(),
        "AcceptedConnectionsLimit.delay" to CardanoUnitBodies.ACCEPT_DELAY.toString(),
        "RTS.N" to CardanoUnitBodies.RTS_THREADS.toString(),
        "LimitNOFILE" to CardanoUnitBodies.NODE_NOFILE.toString(),
        "TimeoutStopSec" to CardanoUnitBodies.STOP_TIMEOUT_SEC.toString(),
        // indexer: Ogmios
        "Ogmios.host" to CardanoIndexerBodies.OGMIOS_HOST,
        "Ogmios.timeout" to CardanoIndexerBodies.OGMIOS_TIMEOUT_SEC.toString(),
        "Ogmios.max-in-flight" to CardanoIndexerBodies.OGMIOS_MAX_IN_FLIGHT.toString(),
        "Ogmios.log-level" to CardanoIndexerBodies.OGMIOS_LOG_LEVEL,
        "Ogmios.LimitNOFILE" to CardanoIndexerBodies.OGMIOS_NOFILE.toString(),
        // indexer: db-sync
        "DbSync.IPAddressAllow" to CardanoIndexerBodies.DBSYNC_IP_ALLOW,
        "DbSync.ledger_backend" to CardanoIndexerBodies.DBSYNC_LEDGER_BACKEND,
        "DbSync.insert_options.tx_cbor" to CardanoDbSyncConfig.TX_CBOR,
        "DbSync.insert_options.tx_out.value" to CardanoDbSyncConfig.TX_OUT_VALUE,
        "DbSync.insert_options.tx_out.use_address_table" to CardanoDbSyncConfig.TX_OUT_USE_ADDRESS_TABLE.toString(),
        "DbSync.insert_options.ledger" to CardanoDbSyncConfig.LEDGER,
        "DbSync.insert_options.shelley.enable" to CardanoDbSyncConfig.SHELLEY.toString(),
        "DbSync.insert_options.multi_asset.enable" to CardanoDbSyncConfig.MULTI_ASSET.toString(),
        "DbSync.insert_options.metadata.enable" to CardanoDbSyncConfig.METADATA.toString(),
        "DbSync.insert_options.plutus.enable" to CardanoDbSyncConfig.PLUTUS.toString(),
        "DbSync.insert_options.governance" to CardanoDbSyncConfig.GOVERNANCE,
        "DbSync.insert_options.offchain_pool_data" to CardanoDbSyncConfig.OFFCHAIN_POOL_DATA,
        "DbSync.insert_options.offchain_vote_data" to CardanoDbSyncConfig.OFFCHAIN_VOTE_DATA,
        "DbSync.insert_options.pool_stat" to CardanoDbSyncConfig.POOL_STAT,
        "DbSync.insert_options.json_type" to CardanoDbSyncConfig.JSON_TYPE,
        "DbSync.insert_options.disable_epoch" to CardanoDbSyncConfig.DISABLE_EPOCH.toString(),
        "DbSync.LimitNOFILE" to CardanoIndexerBodies.DBSYNC_NOFILE.toString(),
        "DbSync.TimeoutStopSec" to CardanoIndexerBodies.DBSYNC_STOP_TIMEOUT_SEC.toString(),
        "Postgres.role.maintenance_work_mem_min_mb" to CardanoIndexerBodies.PG_MAINT_MIN_MB.toString(),
        "Postgres.role.max_parallel_maintenance_workers" to CardanoIndexerBodies.PG_MAX_PARALLEL_MAINTENANCE_WORKERS.toString(),
        "Postgres.role.random_page_cost" to CardanoIndexerBodies.PG_RANDOM_PAGE_COST,
        "Postgres.role.effective_io_concurrency" to CardanoIndexerBodies.PG_EFFECTIVE_IO_CONCURRENCY.toString(),
    )

    @Test
    fun literal_bindings_equal_the_applied_settings()
    {
        val problems = mutableListOf<String>()
        for ((key, value) in literals)
        {
            val want = applied[key]
            if (want == null) problems += "binding $key=$value has no applied constant"
            else if (want != value) problems += "$key binding=$value applied=$want"
        }
        for (key in applied.keys)
        {
            if (key !in literals) problems += "applied $key has no binding"
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun the_unit_and_the_config_files_apply_those_values()
    {
        // Ogmios: the flags
        val args = CardanoIndexerBodies.ogmiosArgs("/n", "/n/node.socket", 41210)
        assertEquals("127.0.0.1", args[args.indexOf("--host") + 1])
        assertEquals("90", args[args.indexOf("--timeout") + 1])
        assertEquals("1000", args[args.indexOf("--max-in-flight") + 1])
        assertEquals("Info", args[args.indexOf("--log-level") + 1])
        val ogmios = CardanoIndexerBodies.ogmiosUnit("mainnet", "/n", "/n/node.socket", "/n/bin/ogmios", "/n/logs/o.log")
        assertTrue(ogmios.contains("LimitNOFILE=${literals.getValue("Ogmios.LimitNOFILE")}"))
        // db-sync: unit and config file
        val dbsync = CardanoIndexerBodies.dbSyncUnit("mainnet", "/n", "/n/bin/run.sh", "/n/logs/d.log")
        assertTrue(dbsync.contains("LimitNOFILE=${literals.getValue("DbSync.LimitNOFILE")}"))
        assertTrue(dbsync.contains("TimeoutStopSec=${literals.getValue("DbSync.TimeoutStopSec")}"))
        assertTrue(dbsync.contains("IPAddressDeny=any"))
        assertTrue(dbsync.contains("IPAddressAllow=${literals.getValue("DbSync.IPAddressAllow")}"))
        val cfg = Json.parseToJsonElement(
            CardanoDbSyncConfig.configJson(resource("chains/cardano/db-sync-config.base.json"), "mainnet", 41217),
        ).jsonObject
        val opts = cfg.getValue("insert_options").jsonObject
        assertEquals(literals.getValue("DbSync.insert_options.tx_cbor"), opts.getValue("tx_cbor").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.tx_out.value"), opts.getValue("tx_out").jsonObject.getValue("value").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.tx_out.use_address_table"), opts.getValue("tx_out").jsonObject.getValue("use_address_table").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.ledger"), opts.getValue("ledger").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.shelley.enable"), opts.getValue("shelley").jsonObject.getValue("enable").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.multi_asset.enable"), opts.getValue("multi_asset").jsonObject.getValue("enable").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.metadata.enable"), opts.getValue("metadata").jsonObject.getValue("enable").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.plutus.enable"), opts.getValue("plutus").jsonObject.getValue("enable").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.governance"), opts.getValue("governance").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.offchain_pool_data"), opts.getValue("offchain_pool_data").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.offchain_vote_data"), opts.getValue("offchain_vote_data").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.pool_stat"), opts.getValue("pool_stat").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.json_type"), opts.getValue("json_type").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.insert_options.disable_epoch"), opts.getValue("disable_epoch").jsonPrimitive.content)
        assertEquals(literals.getValue("DbSync.ledger_backend"), cfg.getValue("ledger_backend").jsonPrimitive.content)
        // PostgreSQL role settings sit in the prepare script
        val script = CardanoIndexerBodies.prepareScript("mainnet", "/n/.toolkit", "/i", "/n/logs/p.log")
        assertTrue(script.contains("PG_MAINT_MIN_MB=${literals.getValue("Postgres.role.maintenance_work_mem_min_mb")}"))
        assertTrue(script.contains("max_parallel_maintenance_workers = '${literals.getValue("Postgres.role.max_parallel_maintenance_workers")}'"))
        assertTrue(script.contains("random_page_cost = '${literals.getValue("Postgres.role.random_page_cost")}'"))
        assertTrue(script.contains("effective_io_concurrency = '${literals.getValue("Postgres.role.effective_io_concurrency")}'"))
    }

    @Test
    fun catalog_port_bindings_and_ports_in_clients_yml_equal_the_port_table()
    {
        @Suppress("UNCHECKED_CAST")
        val programs = yaml("chains/cardano/clients.yml")["programs"] as List<Map<String, Any?>>
        for (env in listOf("mainnet", "preprod", "preview"))
        {
            val node = programs.single { it["env"] == env && it["id"] == "cardano-node" }
            @Suppress("UNCHECKED_CAST")
            val ports = (node["ports"] as List<Map<String, Any?>>).associate { it["role"].toString() to (it["port"] as Int) }
            val p = CardanoPortTable.forEnv(env)
            assertEquals(mapOf("p2p" to p.p2p, "metrics" to p.metrics, "ogmios" to p.ogmios, "dbsync_metrics" to p.dbSyncMetrics), ports, env)
            // the optional ports are check-only (never patched into a config), the P2P / metrics ones as before
            @Suppress("UNCHECKED_CAST")
            val policies = (node["ports"] as List<Map<String, Any?>>).associate { it["role"].toString() to it["config"].toString() }
            assertEquals("none", policies["ogmios"])
            assertEquals("none", policies["dbsync_metrics"])
            assertEquals(setOf("cardano-node", "mithril-client", "ogmios", "cardano-db-sync"), programs.filter { it["env"] == env }.map { it["id"] }.toSet(), env)
        }
        val roles = (bindings.filter { it["source"] == "catalog_port" }.map { it["role"].toString() }).toSet()
        assertEquals(setOf("p2p", "metrics", "ogmios", "dbsync_metrics"), roles)
    }

    @Test
    fun indexer_bindings_are_gated_on_the_install_option_and_file_bindings_are_marked()
    {
        val indexerKeys = bindings.map { it["key"].toString() }.filter { it.startsWith("Ogmios.") || it.startsWith("DbSync.") || it.startsWith("Postgres.") }
        assertTrue(indexerKeys.isNotEmpty())
        for (b in bindings.filter { it["key"].toString() in indexerKeys })
        {
            assertEquals("indexer", b["whenInstallOption"], b["key"].toString())
            val values = b["whenInstallOptionValue"].toString().split(',').map { it.trim() }
            val ogmios = b["key"].toString().startsWith("Ogmios.")
            val expected = if (ogmios) listOf("ogmios", "both") else listOf("dbsync", "both")
            assertEquals(expected, values, b["key"].toString())
        }
        // paths of generated files / the socket must not be created as directories by the client sync
        val files = bindings.filter { it["file"] == true }.map { it["key"].toString() }.toSet()
        assertEquals(setOf("socket-path", "config", "topology", "DbSync.config"), files)
    }

    @Test
    fun indexer_disk_role_and_diagnostics_are_declared()
    {
        val net = yaml("chains/cardano/network.yml")
        @Suppress("UNCHECKED_CAST")
        val roles = (net["diskRoles"] as List<Map<String, Any?>>).map { it["id"].toString() }
        assertEquals(listOf("blockchain", "indexer"), roles)
        @Suppress("UNCHECKED_CAST")
        val diagnostics = (net["diagnostics"] as List<Map<String, Any?>>).associate { it["id"].toString() to it["port"]?.toString() }
        assertEquals("ogmios", diagnostics["cardano_ogmios"])
        assertEquals("dbsync_metrics", diagnostics["cardano_dbsync"])
    }

    @Test
    fun artifacts_follow_the_verified_release_asset_names()
    {
        @Suppress("UNCHECKED_CAST")
        val programs = yaml("chains/cardano/clients.yml")["programs"] as List<Map<String, Any?>>
        for (env in listOf("mainnet", "preprod", "preview"))
        {
            @Suppress("UNCHECKED_CAST")
            fun artifact(id: String) = ((programs.single { it["env"] == env && it["id"] == id }["artifacts"] as List<Map<String, Any?>>).single())
            val ogmios = artifact("ogmios")
            assertEquals("https://github.com/CardanoSolutions/ogmios/releases/download/{tag}/ogmios-{tag}-x86_64-linux.zip", ogmios["urlTemplate"])
            assertEquals("https://github.com/CardanoSolutions/ogmios/releases/download/{tag}/ogmios-{tag}-aarch64-linux.zip", ogmios["urlTemplateAarch64"])
            val dbsync = artifact("cardano-db-sync")
            assertEquals("https://github.com/IntersectMBO/cardano-db-sync/releases/download/{tag}/cardano-db-sync-{tag}-linux.tar.gz", dbsync["urlTemplate"])
            assertEquals(null, dbsync["urlTemplateAarch64"], "upstream ships no aarch64 db-sync")
            assertTrue(dbsync["name"].toString().contains("x86_64"), "the on-disk name carries the arch so aarch64 hosts skip it")
        }
    }
}
