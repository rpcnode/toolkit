package rpcnode.toolkit.chains.ton.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.clients.infrastructure.catalog.YamlClientProgramCatalog

class TonIndexerScriptTest
{
    private val script = TonUnitBodies.indexerScript(
        env = "testnet",
        stateDir = "/data/ton/.toolkit",
        indexerDir = "/data/ton-indexer",
        logFile = "/data/ton/logs/ton-indexer.log",
        bootstrapMarker = "/data/ton/.toolkit/bootstrap.done",
        ports = TonPorts.forEnv("testnet"),
    )

    @Test
    fun renders_without_leftover_placeholders_and_with_the_ports_and_dirs()
    {
        assertFalse(script.contains("{{"), "unresolved placeholder in indexer script")
        assertTrue(script.contains("HTTP_PORT=8082"))
        assertTrue(script.contains("API_PORT=8188"))
        assertTrue(script.contains("REDIS_PORT=16388"))
        assertTrue(script.contains("IS_TESTNET=1"))
        assertTrue(script.contains("IDX='/data/ton-indexer'"))
        assertTrue(script.contains("BOOT_MARKER='/data/ton/.toolkit/bootstrap.done'"))
        assertTrue(script.contains("MAX_LAG=${TonUnitBodies.INDEXER_MAX_LAG_SEC}"))
        assertTrue(script.contains(":-${TonUnitBodies.INDEXER_RELEASE_TAG}}"))
        assertTrue(script.contains(":-${TonUnitBodies.INDEXER_GO_VERSION}}"))
    }

    @Test
    fun uses_the_host_postgresql_without_docker_and_serves_the_api_on_all_interfaces()
    {
        assertFalse(script.contains("docker", ignoreCase = true), "no Docker anywhere in the indexer")
        // an existing server is detected first; only then is one installed
        assertTrue(script.indexOf("detect_postgres") < script.indexOf("apt-get install -y postgresql"))
        assertTrue(script.contains("create role \$PG_ROLE login password"))
        assertTrue(script.contains("create tablespace \$PG_TS owner \$PG_ROLE location '\$TS_DIR'"))
        assertTrue(script.contains("create database \$PG_DB owner \$PG_ROLE tablespace \$PG_TS"))
        assertTrue(script.contains("PG_ROLE=\"rpcnode_ton_\${ENV_ID}\""), "a role/database named after the env, never the shared postgres one")
        assertFalse(script.contains("postgres@127.0.0.1"), "the apps never log in as the postgres superuser")
        assertTrue(script.contains("-bind \"0.0.0.0:\$API_PORT\""))
        assertTrue(script.contains("--bind 127.0.0.1"))
        assertTrue(script.contains("git clone --branch \"\$TAG\""))
    }

    @Test
    fun an_existing_server_is_only_added_to_and_a_new_one_is_tuned()
    {
        // server-wide settings are written only for a server this script installed (PG_BY_US) — otherwise just a suggestion
        assertTrue(script.contains("if (( PG_BY_US == 1 )); then"))
        assertTrue(script.contains("existing PostgreSQL server left untouched"))
        assertTrue(script.contains("90-rpcnode-ton-indexer.conf"))
        assertTrue(script.contains("shared_buffers = \${shared}MB"))
        assertTrue(script.contains("alter role \$PG_ROLE set work_mem"))
    }

    @Test
    fun remove_hook_drops_the_database_tablespace_and_role_and_keeps_the_server()
    {
        val hook = TonUnitBodies.removeHook("/data/ton/.toolkit", "/data/ton-indexer")
        assertFalse(hook.contains("{{"))
        assertTrue(hook.contains("PWDIR='/data/ton/.toolkit/ton-indexer'"))
        assertTrue(hook.contains("IDX='/data/ton-indexer'"))
        val order = listOf("drop database if exists", "drop tablespace if exists", "drop role if exists").map { hook.indexOf(it) }
        assertTrue(order.all { it > 0 } && order == order.sorted(), "database, then tablespace, then role")
        assertFalse(hook.contains("apt-get remove") || hook.contains("purge"), "the PostgreSQL server stays")
        assertTrue(hook.contains("\$PG_TS_DIR\" == \"\$IDX\"/*"), "only a directory inside the indexer dir is deleted")
        assertEquals("bin/rpcnode-on-remove.sh", TonUnitBodies.REMOVE_HOOK_SCRIPT)
        // MyTonCtrl: services, programs and sources go; user data only with a wipe; /var/ton-work only as a link
        for (unit in listOf("validator.service", "mytoncore.service", "ton_http_api.service", "ton-http-api.service"))
        {
            assertTrue(hook.contains(unit), unit)
        }
        assertTrue(hook.contains("rm -rf /usr/bin/ton /usr/src/ton /usr/src/mytonctrl"))
        assertTrue(hook.contains("if [[ -L /var/ton-work ]]; then rm -f /var/ton-work; fi"))
        assertTrue(hook.contains("if [[ \"\$WIPE\" == 1 ]]; then rm -rf /root/.local/share/mytoncore"))
        assertFalse(hook.contains("rm -rf /var/ton-work"), "the chain data is never deleted by the hook")
        assertEquals(
            3,
            Regex("^\\s*remove_mytonctrl$", RegexOption.MULTILINE).findAll(hook).count(),
            "MyTonCtrl is removed on every path out of the hook: no database, database unreachable, database dropped",
        )
        val file = Files.createTempFile("ton-hook", ".sh")
        try
        {
            Files.writeString(file, hook)
            val p = ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start()
            assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
        }
        finally
        {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun mainnet_is_not_flagged_testnet()
    {
        val main = TonUnitBodies.indexerScript("mainnet", "/n/.toolkit", "/i", "/n/l.log", "/n/.toolkit/b.done", TonPorts.forEnv("mainnet"))
        assertTrue(main.contains("IS_TESTNET=0"))
        assertTrue(main.contains("API_PORT=8187"))
    }

    @Test
    fun is_valid_bash()
    {
        val file = Files.createTempFile("ton-indexer", ".sh")
        try
        {
            Files.writeString(file, script)
            val p = ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(0, p.waitFor(), "bash -n failed: $out")
        }
        finally
        {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun unit_waits_for_postgresql_and_carries_no_placeholders()
    {
        val unit = TonUnitBodies.indexerUnit("mainnet", "/data/ton/bin/rpcnode-ton-indexer.sh")
        assertFalse(unit.contains("{{"))
        assertFalse(unit.contains("docker"), "no Docker dependency")
        assertTrue(unit.contains("After=network-online.target postgresql.service"))
        assertTrue(unit.contains("ExecStart=/data/ton/bin/rpcnode-ton-indexer.sh"))
        assertTrue(unit.contains("EnvironmentFile=-/etc/rpcnode/ton.env"))
        assertEquals("rpcnode-ton-mainnet-indexer.service", TonUnitBodies.indexerUnitName("mainnet"))
    }

    @Test
    fun catalog_ports_match_ton_ports()
    {
        val tonPorts = YamlClientProgramCatalog().all().filter { it.network.value == "ton" }
            .associate { it.env.value to it.ports.associate { p -> p.role to p.port } }
        for (env in listOf("mainnet", "testnet"))
        {
            val ports = TonPorts.forEnv(env)
            assertEquals(ports.http, tonPorts.getValue(env)["http"], "$env http")
            assertEquals(ports.p2p, tonPorts.getValue(env)["p2p"], "$env p2p")
            assertEquals(ports.indexer, tonPorts.getValue(env)["indexer"], "$env indexer")
            assertEquals(null, tonPorts.getValue(env)["indexer_db"], "$env: the database is the host's PostgreSQL, no catalog port")
            assertEquals(ports.indexerRedis, tonPorts.getValue(env)["indexer_redis"], "$env indexer_redis")
            assertEquals(ports.publicApi, tonPorts.getValue(env)["rpc_public"], "$env rpc_public")
        }
    }

    @Test
    fun stopping_the_node_unit_stops_the_mytonctrl_services_too()
    {
        val unit = TonUnitBodies.nodeUnit("mainnet", "/data/ton", "/data/ton/bin/rpcnode-ton-node-start.sh", "/data/ton/logs/ton.log")
        assertFalse(unit.contains("{{"))
        assertTrue(unit.contains("ExecStop=-/bin/systemctl stop ton_http_api.service ton-http-api.service mytoncore.service validator.service"))
    }
}
