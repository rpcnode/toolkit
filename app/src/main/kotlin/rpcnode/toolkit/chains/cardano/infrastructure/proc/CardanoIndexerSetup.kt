package rpcnode.toolkit.chains.cardano.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoClusters
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoDbSyncConfig
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoHostBinaries
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoIndexerBodies
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoIndexerMode
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoPorts
import rpcnode.toolkit.chains.cardano.infrastructure.CardanoUnitBodies

/**
 * Files and unit bodies of the optional indexer companions (Ogmios, db-sync) under one node dir. Writes only below the node
 * dir; installing the units (`/etc/systemd/system`) is up to the caller via `installCustomUnits`.
 */
object CardanoIndexerSetup
{
    sealed interface Result
    {
        /** [companions] = unit name → body, in start order (prepare → db-sync → Ogmios). */
        data class Ok(val companions: List<Pair<String, String>>) : Result
        data class Failed(val detail: String) : Result
    }

    /** Every companion unit this setup can create; those not in [Result.Ok.companions] are retired by the starter. */
    fun allUnits(env: String): List<String> =
        listOf(
            CardanoIndexerBodies.prepareUnitName(env),
            CardanoIndexerBodies.dbSyncUnitName(env),
            CardanoIndexerBodies.ogmiosUnitName(env),
        )

    fun prepare(
        root: Path,
        env: String,
        mode: CardanoIndexerMode,
        indexerDir: Path,
        ports: CardanoPorts,
        socket: Path,
    ): Result
    {
        val envId = CardanoClusters.normalizeEnv(env)
        val toolkit = root.resolve(".toolkit")
        try
        {
            Files.createDirectories(toolkit)
            Files.createDirectories(root.resolve("logs"))
            Files.writeString(
                root.resolve(CardanoIndexerBodies.STATE_FILE),
                """{"mode":"${mode.id}","env":"$envId"}""" + "\n",
            )
        }
        catch (e: Exception)
        {
            return Result.Failed(e.message ?: "write indexer state failed")
        }
        val companions = mutableListOf<Pair<String, String>>()
        if (mode.dbSync)
        {
            when (val r = prepareDbSync(root, envId, indexerDir, ports, socket))
            {
                is Result.Failed -> return r
                is Result.Ok -> companions += r.companions
            }
        }
        if (mode.ogmios)
        {
            when (val r = prepareOgmios(root, envId, ports, socket))
            {
                is Result.Failed -> return r
                is Result.Ok -> companions += r.companions
            }
        }
        return Result.Ok(companions)
    }

    private fun prepareOgmios(root: Path, env: String, ports: CardanoPorts, socket: Path): Result
    {
        val bin = when (val ensured = CardanoHostBinaries.ensureOgmios(root))
        {
            is CardanoHostBinaries.Unpacked.Failed -> return Result.Failed(ensured.detail)
            is CardanoHostBinaries.Unpacked.Ok -> ensured.value
        }
        val execStart = (listOf(bin.toString()) + CardanoIndexerBodies.ogmiosArgs(root.toString(), socket.toString(), ports.ogmios))
            .joinToString(" ")
        val body = CardanoIndexerBodies.ogmiosUnit(
            env = env,
            nodeDir = root.toString(),
            socketPath = socket.toString(),
            execStart = execStart,
            logFile = root.resolve(CardanoIndexerBodies.OGMIOS_LOG).toString(),
        )
        return Result.Ok(listOf(CardanoIndexerBodies.ogmiosUnitName(env) to body))
    }

    private fun prepareDbSync(root: Path, env: String, indexerDir: Path, ports: CardanoPorts, socket: Path): Result
    {
        val bin = when (val ensured = CardanoHostBinaries.ensureDbSync(root))
        {
            is CardanoHostBinaries.Unpacked.Failed -> return Result.Failed(ensured.detail)
            is CardanoHostBinaries.Unpacked.Ok -> ensured.value
        }
        val toolkit = root.resolve(".toolkit")
        val pwDir = root.resolve(CardanoIndexerBodies.PW_DIR)
        val ledgerState = indexerDir.resolve(CardanoIndexerBodies.LEDGER_STATE_DIR)
        val prepareScript = root.resolve(CardanoIndexerBodies.PREPARE_SCRIPT)
        val runScript = root.resolve(CardanoIndexerBodies.RUN_SCRIPT)
        val hook = root.resolve(CardanoIndexerBodies.REMOVE_HOOK)
        try
        {
            Files.createDirectories(indexerDir)
            Files.createDirectories(ledgerState)
            Files.createDirectories(root.resolve("bin"))
            Files.createDirectories(pwDir)
            runCatching { Files.setPosixFilePermissions(pwDir, PosixFilePermissions.fromString("rwx------")) }
            val base = checkNotNull(javaClass.classLoader.getResourceAsStream("chains/cardano/db-sync-config.base.json")) {
                "missing chains/cardano/db-sync-config.base.json"
            }.use { it.readBytes().toString(Charsets.UTF_8) }
            Files.writeString(
                root.resolve(CardanoUnitBodies.CONFIG_DIR).resolve(CardanoIndexerBodies.DBSYNC_CONFIG),
                CardanoDbSyncConfig.configJson(base, env, ports.dbSyncMetrics),
            )
            Files.writeString(
                prepareScript,
                CardanoIndexerBodies.prepareScript(
                    env = env,
                    stateDir = toolkit.toString(),
                    indexerDir = indexerDir.toString(),
                    logFile = root.resolve(CardanoIndexerBodies.PREPARE_LOG).toString(),
                ),
            )
            val dbSyncCommand = listOf(bin.toString()) +
                CardanoIndexerBodies.dbSyncArgs(root.toString(), socket.toString(), ledgerState.toString())
            Files.writeString(
                runScript,
                CardanoIndexerBodies.runScript(
                    readyMarker = root.resolve(CardanoIndexerBodies.READY_MARKER).toString(),
                    socketPath = socket.toString(),
                    pgpassFile = root.resolve(CardanoIndexerBodies.PGPASS).toString(),
                    stateDir = ledgerState.toString(),
                    execStart = CardanoIndexerBodies.shellCommand(dbSyncCommand),
                ),
            )
            // written once db-sync was enabled and never removed: dropping the data is the node removal's job
            Files.writeString(hook, CardanoIndexerBodies.removeHook(pwDir.toString(), indexerDir.toString()))
            for (script in listOf(prepareScript, runScript, hook))
            {
                makeExecutable(script)
            }
        }
        catch (e: Exception)
        {
            return Result.Failed(e.message ?: "write db-sync files failed")
        }
        val prepareUnit = CardanoIndexerBodies.prepareUnit(env, prepareScript.toString())
        val runUnit = CardanoIndexerBodies.dbSyncUnit(
            env = env,
            nodeDir = root.toString(),
            execStart = runScript.toString(),
            logFile = root.resolve(CardanoIndexerBodies.DBSYNC_LOG).toString(),
        )
        return Result.Ok(
            listOf(
                CardanoIndexerBodies.prepareUnitName(env) to prepareUnit,
                CardanoIndexerBodies.dbSyncUnitName(env) to runUnit,
            ),
        )
    }

    private fun makeExecutable(path: Path)
    {
        runCatching {
            val perms = Files.getPosixFilePermissions(path).toMutableSet()
            perms += PosixFilePermission.OWNER_EXECUTE
            perms += PosixFilePermission.GROUP_EXECUTE
            perms += PosixFilePermission.OTHERS_EXECUTE
            Files.setPosixFilePermissions(path, perms)
        }
    }
}
