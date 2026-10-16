package rpcnode.toolkit.chains.cardano.infrastructure

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class CardanoIndexerBodiesTest
{
    private val prepare = CardanoIndexerBodies.prepareScript("preprod", "/data/c/.toolkit", "/mnt/idx/cardano", "/data/c/logs/cardano-dbsync-prepare.log")
    private val run = CardanoIndexerBodies.runScript(
        readyMarker = "/data/c/.toolkit/cardano-dbsync/ready",
        socketPath = "/data/c/node.socket",
        pgpassFile = "/data/c/.toolkit/cardano-dbsync/pgpass",
        stateDir = "/mnt/idx/cardano/ledger-state",
        execStart = CardanoIndexerBodies.shellCommand(
            listOf("/data/c/bin/cardano-db-sync") + CardanoIndexerBodies.dbSyncArgs("/data/c", "/data/c/node.socket", "/mnt/idx/cardano/ledger-state"),
        ),
    )
    private val hook = CardanoIndexerBodies.removeHook("/data/c/.toolkit/cardano-dbsync", "/mnt/idx/cardano")

    @Test
    fun nothing_is_left_unrendered()
    {
        for (text in listOf(prepare, run, hook))
        {
            assertFalse(text.contains("{{"), "unresolved placeholder")
        }
        assertFalse(CardanoIndexerBodies.ogmiosUnit("mainnet", "/n", "/n/node.socket", "/n/bin/ogmios x", "/n/l").contains("{{"))
        assertFalse(CardanoIndexerBodies.dbSyncUnit("mainnet", "/n", "/n/bin/r.sh", "/n/l").contains("{{"))
        assertFalse(CardanoIndexerBodies.prepareUnit("mainnet", "/n/bin/p.sh").contains("{{"))
    }

    @Test
    fun scripts_are_valid_bash()
    {
        val dir = Files.createTempDirectory("cardano-scripts")
        try
        {
            for ((name, text) in mapOf("prepare" to prepare, "run" to run, "hook" to hook))
            {
                val file = dir.resolve("$name.sh")
                Files.writeString(file, text)
                val p = runCatching { ProcessBuilder("bash", "-n", file.toString()).redirectErrorStream(true).start() }.getOrNull() ?: return
                val out = p.inputStream.bufferedReader().readText()
                assertEquals(0, p.waitFor(), "$name: $out")
            }
        }
        finally
        {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun ogmios_binds_loopback_and_follows_the_node_unit()
    {
        val args = CardanoIndexerBodies.ogmiosArgs("/data/c", "/data/c/node.socket", 41220)
        assertEquals(
            listOf(
                "--node-socket", "/data/c/node.socket", "--node-config", "/data/c/config/config.json", "--host", "127.0.0.1",
                "--port", "41220", "--timeout", "90", "--max-in-flight", "1000", "--log-level", "Info",
            ),
            args,
        )
        assertFalse(args.contains("0.0.0.0"))
        val unit = CardanoIndexerBodies.ogmiosUnit("preprod", "/data/c", "/data/c/node.socket", "/data/c/bin/ogmios ${args.joinToString(" ")}", "/data/c/logs/cardano-ogmios.log")
        assertTrue(unit.contains("PartOf=rpcnode-cardano-preprod.service"))
        assertTrue(unit.contains("After=network-online.target rpcnode-cardano-preprod.service"))
        assertTrue(unit.contains("Restart=always"))
        assertTrue(unit.contains("ExecStart=/data/c/bin/ogmios --node-socket"))
        assertTrue(unit.contains("LimitNOFILE=1048576"))
        assertEquals("rpcnode-cardano-preprod-ogmios.service", CardanoIndexerBodies.ogmiosUnitName("preprod"))
    }

    @Test
    fun db_sync_unit_is_confined_to_loopback_and_ordered_after_the_preparation()
    {
        val unit = CardanoIndexerBodies.dbSyncUnit("mainnet", "/data/c", "/data/c/bin/rpcnode-cardano-dbsync.sh", "/data/c/logs/cardano-dbsync.log")
        assertTrue(unit.contains("IPAddressDeny=any"))
        assertTrue(unit.contains("IPAddressAllow=localhost"))
        assertTrue(unit.contains("Wants=rpcnode-cardano-mainnet-dbsync-prepare.service"))
        assertTrue(unit.contains("After=network-online.target postgresql.service rpcnode-cardano-mainnet.service rpcnode-cardano-mainnet-dbsync-prepare.service"))
        assertTrue(unit.contains("TimeoutStopSec=300"))
        // a single wrapper script as ExecStart: no database password, no flag, nothing in the unit text
        assertTrue(unit.contains("ExecStart=/data/c/bin/rpcnode-cardano-dbsync.sh\n"))
        assertFalse(unit.contains("password", ignoreCase = true))
        // the preparation unit returns at once (the first run may install PostgreSQL) and stays active afterwards
        val prep = CardanoIndexerBodies.prepareUnit("mainnet", "/data/c/bin/rpcnode-cardano-dbsync-prepare.sh")
        assertTrue(prep.contains("Type=simple"))
        assertTrue(prep.contains("RemainAfterExit=yes"))
        assertTrue(prep.contains("Restart=on-failure"))
        assertFalse(prep.contains("Type=oneshot"))
    }

    @Test
    fun db_sync_command_line_has_no_secret_and_reads_the_pgpass_file()
    {
        val args = CardanoIndexerBodies.dbSyncArgs("/data/c", "/data/c/node.socket", "/mnt/idx/cardano/ledger-state")
        assertEquals(
            listOf(
                "--config", "/data/c/config/db-sync-config.json", "--socket-path", "/data/c/node.socket",
                "--state-dir", "/mnt/idx/cardano/ledger-state", "--schema-dir", "/data/c/schema",
            ),
            args,
        )
        assertTrue(run.contains("export PGPASSFILE=\"\$PGPASS\""))
        assertTrue(run.contains("PGPASS='/data/c/.toolkit/cardano-dbsync/pgpass'"))
        assertTrue(run.contains("exec '/data/c/bin/cardano-db-sync' '--config' '/data/c/config/db-sync-config.json'"))
        assertTrue(run.indexOf("until [[ -f \"\$READY\"") < run.indexOf("exec "), "waits for the preparation and the node socket before exec")
    }

    @Test
    fun the_password_never_reaches_argv_and_files_are_private()
    {
        // SQL with the password goes through stdin (heredoc), never `-c "... password '...'"`
        assertFalse(Regex("""-c\s+"[^"]*password""").containsMatchIn(prepare), "password in a psql -c argument")
        assertTrue(prepare.contains("psql_su -d postgres -f - <<SQL"))
        assertTrue(prepare.contains("create role \$PG_ROLE login password '\$pw';"))
        assertTrue(prepare.contains("umask 077"))
        assertTrue(prepare.contains("chmod 600 \"\$PGPASS\""))
        assertTrue(prepare.contains("chmod 600 \"\$PW_FILE\""))
        assertTrue(prepare.contains("chmod 700 \"\$PWDIR\""))
        // exactly the 5 colon separated fields db-sync parses (host:port:db:user:password)
        assertTrue(prepare.contains("printf '127.0.0.1:%s:%s:%s:%s\\n' \"\$PG_PORT\" \"\$PG_DB\" \"\$PG_ROLE\" \"\$pw\""))
        assertFalse(prepare.contains("set -x"))
    }

    @Test
    fun postgresql_of_the_host_is_reused_and_only_added_to()
    {
        assertTrue(prepare.indexOf("detect_postgres") < prepare.indexOf("apt-get install -y postgresql"))
        assertTrue(prepare.contains("PG_ROLE=\"rpcnode_cardano_\${ENV_ID}\""))
        assertTrue(prepare.contains("create tablespace \$PG_TS owner \$PG_ROLE location '\$TS_DIR'"))
        assertTrue(prepare.contains("create database \$PG_DB owner \$PG_ROLE template template0 encoding 'UTF8' tablespace \$PG_TS"))
        assertFalse(prepare.contains("postgres@127.0.0.1"), "the application never logs in as the postgres superuser")
        assertTrue(prepare.contains("if (( PG_BY_US == 1 )); then"))
        assertTrue(prepare.contains("existing PostgreSQL server left untouched"))
        assertTrue(prepare.contains("90-rpcnode-\$PG_CONF_TAG.conf"))
        assertTrue(prepare.contains("PG_CONF_TAG=\"cardano-dbsync\""))
        assertTrue(prepare.contains("TS_DIR=\"\$IDX/pgdata\""))
        assertFalse(prepare.contains("docker", ignoreCase = true))
        assertEquals("rpcnode_cardano_preprod", CardanoIndexerBodies.pgRole("preprod"))
    }

    @Test
    fun remove_hook_drops_database_tablespace_role_and_keeps_the_server()
    {
        assertTrue(hook.contains("PWDIR='/data/c/.toolkit/cardano-dbsync'"))
        assertTrue(hook.contains("IDX='/mnt/idx/cardano'"))
        val order = listOf("drop database if exists", "drop tablespace if exists", "drop role if exists").map { hook.indexOf(it) }
        assertTrue(order.all { it > 0 } && order == order.sorted(), "database, then tablespace, then role")
        assertFalse(hook.contains("apt-get remove") || hook.contains("purge"))
        assertTrue(hook.contains("\"\$PG_TS_DIR\" == \"\$IDX\"/*"), "only a directory inside the indexer dir is deleted")
        assertEquals("bin/rpcnode-on-remove.sh", CardanoIndexerBodies.REMOVE_HOOK)
    }

    @Test
    fun db_sync_config_follows_the_node_config_and_sets_the_options()
    {
        val base = checkNotNull(javaClass.classLoader.getResourceAsStream("chains/cardano/db-sync-config.base.json")).readBytes().toString(Charsets.UTF_8)
        for ((env, magic, port) in listOf(Triple("mainnet", "RequiresNoMagic", 41217), Triple("preprod", "RequiresMagic", 41227), Triple("preview", "RequiresMagic", 41237)))
        {
            val cfg = Json.parseToJsonElement(CardanoDbSyncConfig.configJson(base, env, port)).jsonObject
            assertEquals(env, cfg.getValue("NetworkName").jsonPrimitive.content)
            assertEquals(magic, cfg.getValue("RequiresNetworkMagic").jsonPrimitive.content)
            assertEquals("config.json", cfg.getValue("NodeConfigFile").jsonPrimitive.content, "genesis files + protocol come from the node's config.json")
            assertEquals(port.toString(), cfg.getValue("PrometheusPort").jsonPrimitive.content)
            // the logging block of IOG's file is kept
            assertEquals("StdoutSK", cfg.getValue("setupScribes").jsonArray[0].jsonObject.getValue("scKind").jsonPrimitive.content)
            val opts = cfg.getValue("insert_options").jsonObject
            assertEquals("enable", opts.getValue("tx_out").jsonObject.getValue("value").jsonPrimitive.content)
            assertEquals("disable", opts.getValue("offchain_pool_data").jsonPrimitive.content)
            assertEquals("inmemory", cfg.getValue("ledger_backend").jsonPrimitive.content)
        }
    }
}
