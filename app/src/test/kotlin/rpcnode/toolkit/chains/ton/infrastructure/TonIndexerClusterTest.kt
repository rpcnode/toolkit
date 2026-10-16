package rpcnode.toolkit.chains.ton.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Runs the database part of the real indexer script (`provision_database`) against stand-ins for `pg_createcluster`,
 * `pg_lsclusters`, `psql`, `apt-get`, ... and checks what it would have done to the host. Needs bash 4 (`declare -A` at the top).
 */
class TonIndexerClusterTest
{
    private val bash: String = listOf("/opt/homebrew/bin/bash", "/usr/local/bin/bash", "/bin/bash", "/usr/bin/bash")
        .firstOrNull { Files.isExecutable(Path.of(it)) && version(it) >= 4 } ?: "bash"

    private fun version(bin: String): Int =
        runCatching {
            val p = ProcessBuilder(bin, "-c", "echo \${BASH_VERSINFO[0]}").redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText().trim().toInt().also { p.waitFor() }
        }.getOrDefault(0)

    private class Sandbox(val root: Path)
    {
        val stubs: Path = Files.createDirectories(root.resolve("stubs"))
        val state: Path = Files.createDirectories(root.resolve("state"))
        val idx: Path = Files.createDirectories(root.resolve("idx"))
        val lib: Path = root.resolve("usr-lib-postgresql")
        val etc: Path = root.resolve("etc-postgresql")
        val log: Path = root.resolve("indexer.log")
        val calls: Path = root.resolve("calls.log")
        val clusters: Path = root.resolve("clusters")
        val pwDir: Path = state.resolve("ton-indexer")

        fun calls(): List<String> = if (Files.exists(calls)) Files.readAllLines(calls) else emptyList()
        fun log(): String = if (Files.exists(log)) Files.readString(log) else ""
    }

    private fun stub(sb: Sandbox, name: String, body: String)
    {
        val f = sb.stubs.resolve(name)
        Files.writeString(f, "#!/bin/bash\n$body\n")
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    private fun sandbox(existingServer: Boolean): Sandbox
    {
        val sb = Sandbox(Files.createTempDirectory("ton-cluster"))
        val c = sb.calls.toString()
        val cl = sb.clusters.toString()
        val up = sb.root.toString()
        if (existingServer)
        {
            Files.createDirectories(sb.lib.resolve("16"))
            Files.writeString(sb.clusters, "16 main 5432 online postgres /var/lib/postgresql/16/main /var/log/postgresql/main.log\n")
            Files.writeString(sb.root.resolve("up-5432"), "")
        }
        else
        {
            Files.writeString(sb.clusters, "")
        }
        stub(sb, "apt-get", "echo \"apt-get \$*\" >> '$c'; mkdir -p '${sb.lib}/16'; " +
            "echo '16 main 5432 online postgres /var/lib/postgresql/16/main /var/log/postgresql/main.log' >> '$cl'")
        stub(sb, "pg_lsclusters", "cat '$cl'")
        // registers the cluster with the port it was asked for (-p), like the real tool
        stub(sb, "pg_createcluster", "echo \"pg_createcluster \$*\" >> '$c'; name=\$2; port=5432; " +
            "while [ \$# -gt 0 ]; do [ \"\$1\" = -p ] && port=\$2; [ \"\$1\" = -d ] && dir=\$2; shift; done; " +
            "echo \"16 \$name \$port down postgres \$dir /var/log/postgresql/\$name.log\" >> '$cl'; mkdir -p \"\$dir\"")
        stub(sb, "pg_dropcluster", "echo \"pg_dropcluster \$*\" >> '$c'; grep -v ' main ' '$cl' > '$cl.tmp'; mv '$cl.tmp' '$cl'")
        stub(sb, "pg_ctlcluster", "echo \"pg_ctlcluster \$*\" >> '$c'; port=\$(awk -v n=\"\$2\" '\$2 == n { print \$3 }' '$cl'); touch '$up/up-'\$port")
        stub(sb, "pg_isready", "p=5432; while [ \$# -gt 0 ]; do [ \"\$1\" = -p ] && p=\$2; shift; done; [ -e '$up/up-'\$p ]")
        stub(sb, "setpriv", "while [[ \"\$1\" == --* ]]; do shift; done; exec \"\$@\"")
        val psql = checkNotNull(javaClass.classLoader.getResourceAsStream("ton/psql-stub.sh")) { "missing ton/psql-stub.sh" }
            .readBytes().toString(Charsets.UTF_8).replace("__CALLS__", c).replace("__ROOT__", up)
        stub(sb, "psql", psql.lines().filterNot { it.startsWith("#!") }.joinToString("\n"))
        stub(sb, "systemctl", "echo \"systemctl \$*\" >> '$c'")
        stub(sb, "chown", "exit 0")
        stub(sb, "nproc", "echo 8")
        return sb
    }

    /** The script up to (not including) its main flow, then the two calls under test. Returns the exit code of provision_database. */
    private fun provision(sb: Sandbox, env: String = "testnet"): Int
    {
        val script = TonUnitBodies.indexerScript(
            env = env,
            stateDir = sb.state.toString(),
            indexerDir = sb.idx.toString(),
            logFile = sb.log.toString(),
            bootstrapMarker = sb.root.resolve("boot.done").toString(),
            ports = TonPorts.forEnv(env),
        )
        val cut = script.indexOf("log \"TON indexer for")
        assertTrue(cut > 0, "main flow marker not found")
        val harness = script.substring(0, cut) + "\nensure_password\nprovision_database\necho \"rc=\$?\"\n"
        val file = sb.root.resolve("harness.sh")
        Files.writeString(file, harness)
        val pb = ProcessBuilder(bash, file.toString()).redirectErrorStream(true)
        pb.environment()["PATH"] = sb.stubs.toString() + ":" + System.getenv("PATH")
        pb.environment()["RPCNODE_PG_LIB_DIR"] = sb.lib.toString()
        pb.environment()["RPCNODE_PG_ETC"] = sb.etc.toString()
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        val rc = Regex("rc=(\\d+)").find(sb.log())?.groupValues?.get(1)?.toInt()
            ?: error("no rc line.\nscript output: $out\nlog:\n${sb.log()}")
        if (rc != 0) println("indexer log:\n${sb.log()}\nstub calls:\n${sb.calls().joinToString("\n")}")
        return rc
    }

    private fun requireBash4() = assumeTrue(version(bash) >= 4, "bash 4+ is needed to run the indexer script")

    @Test
    fun a_fresh_host_gets_one_cluster_of_its_own_and_no_stock_main_cluster()
    {
        requireBash4()
        val sb = sandbox(existingServer = false)
        assertEquals(0, provision(sb))
        val calls = sb.calls()
        assertTrue(calls.any { it.startsWith("apt-get install") && it.contains("postgresql") }, "installs the packages: $calls")
        assertTrue(calls.contains("pg_dropcluster --stop 16 main"), "the main cluster the package just created is dropped again: $calls")
        val create = calls.single { it.startsWith("pg_createcluster") }
        assertEquals("pg_createcluster 16 rpcnode-ton-testnet -d ${sb.idx}/pgcluster -p 15433 --start-conf=auto", create)
        assertTrue(calls.any { it.startsWith("pg_ctlcluster 16 rpcnode-ton-testnet start") })
        assertTrue(calls.any { it.startsWith("systemctl enable postgresql@16-rpcnode-ton-testnet.service") })
        assertTrue(calls.any { it.startsWith("psql -p 15433") && it.contains("create role rpcnode_ton_testnet login password") })
        val createDb = calls.single { it.contains("create database") }
        assertTrue(createDb.contains("create database rpcnode_ton_testnet owner rpcnode_ton_testnet"), createDb)
        assertFalse(createDb.contains("tablespace"), "no tablespace: data and WAL are in the cluster's own directory")
        assertFalse(calls.any { it.contains("create tablespace") })

        val conf = Files.readString(sb.etc.resolve("16/rpcnode-ton-testnet/conf.d/90-rpcnode-ton-indexer.conf"))
        assertTrue(conf.contains("listen_addresses = '127.0.0.1'"))
        assertTrue(conf.contains("max_wal_size = 16GB") && conf.contains("shared_buffers ="))

        val env = Files.readString(sb.pwDir.resolve("pg.env"))
        for (line in listOf("PG_MODE=cluster", "PG_PORT=15433", "PG_ROLE=rpcnode_ton_testnet", "PG_DB=rpcnode_ton_testnet",
            "PG_VERSION=16", "PG_CLUSTER=rpcnode-ton-testnet", "PG_DATA=${sb.idx}/pgcluster", "PG_INSTALLED_BY_US=1"))
        {
            assertTrue(env.lines().contains(line), "pg.env lacks $line:\n$env")
        }
        assertFalse(env.contains("PG_TS="), "an old-layout marker must never be written by the new layout")
        val pgpass = Files.readString(sb.pwDir.resolve("pgpass"))
        assertTrue(pgpass.startsWith("127.0.0.1:15433:rpcnode_ton_testnet:rpcnode_ton_testnet:"))
    }

    @Test
    fun an_existing_server_is_left_alone_and_the_node_still_gets_its_own_cluster()
    {
        requireBash4()
        val sb = sandbox(existingServer = true)
        assertEquals(0, provision(sb))
        val calls = sb.calls()
        assertFalse(calls.any { it.startsWith("apt-get") }, "nothing is installed when PostgreSQL is already there: $calls")
        assertFalse(calls.any { it.startsWith("pg_dropcluster") }, "a cluster that was there before is never dropped: $calls")
        assertTrue(calls.any { it.startsWith("pg_createcluster 16 rpcnode-ton-testnet") })
        assertTrue(calls.none { it.contains("5432") && it.startsWith("psql") }, "the host's own server on 5432 is not used: $calls")
        assertTrue(Files.readString(sb.pwDir.resolve("pg.env")).lines().contains("PG_INSTALLED_BY_US=0"))
    }

    @Test
    fun a_second_run_changes_nothing_on_the_host()
    {
        requireBash4()
        val sb = sandbox(existingServer = true)
        assertEquals(0, provision(sb))
        val firstCalls = sb.calls().size
        assertEquals(0, provision(sb))
        val second = sb.calls().drop(firstCalls)
        assertFalse(second.any { it.startsWith("pg_createcluster") }, "the cluster exists: $second")
        assertFalse(second.any { it.startsWith("pg_ctlcluster") }, "it is up and its config did not change: $second")
        assertFalse(second.any { it.contains("create role") || it.contains("create database") }, "role/db are created once")
    }

    @Test
    fun a_node_installed_with_the_old_layout_keeps_its_shared_server_tablespace_and_is_not_migrated()
    {
        requireBash4()
        val sb = sandbox(existingServer = true)
        Files.createDirectories(sb.pwDir)
        Files.writeString(
            sb.pwDir.resolve("pg.env"),
            "PG_PORT=5432\nPG_ROLE=rpcnode_ton_testnet\nPG_DB=rpcnode_ton_testnet\nPG_TS=rpcnode_ton_testnet_ts\nPG_TS_DIR=${sb.idx}/pgdata\nPG_INSTALLED_BY_US=0\n",
        )
        assertEquals(0, provision(sb))
        val calls = sb.calls()
        assertFalse(calls.any { it.startsWith("pg_createcluster") }, "no new cluster for an old-layout node: $calls")
        assertTrue(calls.any { it.contains("create tablespace rpcnode_ton_testnet_ts") }, "still uses its tablespace: $calls")
        assertTrue(calls.any { it.startsWith("psql -p 5432") })
        assertTrue(Files.readString(sb.pwDir.resolve("pg.env")).contains("PG_TS=rpcnode_ton_testnet_ts"))
    }

    @Test
    fun mainnet_uses_its_own_port_and_names()
    {
        requireBash4()
        val sb = sandbox(existingServer = true)
        assertEquals(0, provision(sb, "mainnet"))
        val create = sb.calls().single { it.startsWith("pg_createcluster") }
        assertEquals("pg_createcluster 16 rpcnode-ton-mainnet -d ${sb.idx}/pgcluster -p 15432 --start-conf=auto", create)
    }
}
