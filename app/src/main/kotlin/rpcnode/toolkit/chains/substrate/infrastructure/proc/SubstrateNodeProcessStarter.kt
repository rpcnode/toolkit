package rpcnode.toolkit.chains.substrate.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmHostBinaries
import rpcnode.toolkit.chains.nonevm.infrastructure.ToolkitArgs
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateDb
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateEnv
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateMode
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpec
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Optional extra units of a Substrate node (a REST API, or the history indexer, next to it). [files] are written under the node
 * dir before the units are installed (path relative to the node dir, executable bit set); [dataFiles] are plain files at absolute
 * paths (the history indexer's project lives in its own `indexer` disk role), written with their directories.
 */
class SubstrateCompanion(
    val units: List<Pair<String, String>>,
    val files: Map<String, String>,
    val dataFiles: Map<Path, String> = emptyMap(),
)

/** What a companion needs to know about this Start: the node's env and type, the install options that concern it, the indexer disk. */
class SubstrateCompanionContext(
    val env: SubstrateEnv,
    val nodeDir: Path,
    /** `install_options.indexer`. */
    val indexerOption: String?,
    val mode: SubstrateMode,
    /** The `indexer` disk role of the wizard (null on a single-disk host: the companion uses `<nodeDir>/indexer`). */
    val indexerDir: Path?,
    /** `install_options.indexer_start_block`, as typed. */
    val startBlock: String?,
)

/**
 * Start of a Substrate node under `node_dir`: binaries into `bin/`, the node's flags into one systemd unit, an optional
 * companion unit. The chain database lives in the `state` role (the node dir itself on a single-disk host).
 */
class SubstrateNodeProcessStarter(
    private val spec: SubstrateSpec,
    private val flags: (SubstrateEnv, SubstrateMode, basePath: String) -> List<String>,
    private val companion: (SubstrateCompanionContext) -> SubstrateCompanion? = { null },
    /** Extra pre-flight (peaq: the chain spec file); a non-null result is the failure text. */
    private val preflight: (SubstrateEnv, nodeDir: Path) -> String? = { _, _ -> null },
    /** Units a [companion] may install: removed when the operator turns the companion off. */
    private val companionUnits: (SubstrateEnv) -> List<String> = { emptyList() },
) : HostNodeProcessStarter
{
    override fun start(
        nodeId: String,
        network: String,
        env: String,
        nodeDir: String,
        launch: NodeLaunchSpec,
    ): HostNodeStartResult
    {
        val root = Path.of(nodeDir.trim()).toAbsolutePath().normalize()
        if (!Files.isDirectory(root))
        {
            return HostNodeStartResult.Failed("node_dir missing: $root")
        }
        if (root.toString().any { it.isWhitespace() })
        {
            return HostNodeStartResult.Failed("node_dir must not contain spaces")
        }
        val e = spec.env(env)
        val mode = SubstrateMode.parse(ToolkitArgs.value(launch.args, "mode"))
        val state = ToolkitArgs.path(launch.args, "state") ?: root

        val bin = when (val r = NonEvmHostBinaries.ensure(root, spec.binaries, NonEvmHostBinaries.Source.Raw))
        {
            is NonEvmHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(r.detail)
            is NonEvmHostBinaries.Result.Ok -> r.bin
        }
        workerMismatch(bin)?.let { return HostNodeStartResult.Failed(it) }
        if (NonEvmHostBinaries.versionOutput(bin.resolve(spec.binary)) == null)
        {
            return HostNodeStartResult.Failed("${spec.binary} does not run on this host (wrong CPU architecture or libc) — check `${bin.resolve(spec.binary)} --version`")
        }
        databaseUnavailable(bin, e, mode)?.let { return HostNodeStartResult.Failed(it) }
        preflight(e, root)?.let { return HostNodeStartResult.Failed(it) }

        try
        {
            Files.createDirectories(state)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
        }
        catch (ex: Exception)
        {
            return HostNodeStartResult.Failed(ex.message ?: "mkdir failed")
        }
        modeConflict(root, state, e, mode)?.let { return HostNodeStartResult.Failed(it) }

        val logRel = spec.relativeLog
        val body = SubstrateUnitBodies.unit(
            spec = spec,
            env = e,
            nodeDir = root.toString(),
            execStart = SubstrateUnitBodies.execStart(bin.resolve(spec.binary).toString(), flags(e, mode, state.toString())),
            logFile = root.resolve(logRel).toString(),
        )
        val extra = try
        {
            val ctx = SubstrateCompanionContext(
                env = e,
                nodeDir = root,
                indexerOption = ToolkitArgs.value(launch.args, "indexer"),
                mode = mode,
                indexerDir = ToolkitArgs.path(launch.args, "indexer-dir"),
                startBlock = ToolkitArgs.value(launch.args, "indexer-start"),
            )
            companion(ctx)?.also { writeFiles(root, it.files); writeData(it.dataFiles) }
        }
        catch (ex: Exception)
        {
            return HostNodeStartResult.Failed(ex.message ?: "write companion files failed")
        }
        // a companion switched off (or swapped for another) must not keep running, or building, next to the node
        val stale = companionUnits(e) - extra?.units?.map { it.first }.orEmpty().toSet()
        if (stale.isNotEmpty())
        {
            HostNodeLaunchSupport.removeUnits(stale, network, e.id)
        }
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = e.id,
            nodeDir = root,
            primaryBody = body,
            companions = extra?.units.orEmpty(),
            launch = launch.copy(entry = "bin/${spec.binary}", logFile = logRel),
        )
    }

    /** The PVF workers must be the same version as each other (the node checks them against itself at its own start). */
    private fun workerMismatch(bin: Path): String?
    {
        if (spec.workers.size < 2) return null
        val versions = spec.workers.map { it to NonEvmHostBinaries.versionOutput(bin.resolve(it)) }
        versions.firstOrNull { it.second == null }?.let { return "${it.first} does not run on this host — check `${bin.resolve(it.first)} --version`" }
        return if (versions.map { it.second }.distinct().size > 1)
        {
            "worker binaries differ in version (${versions.joinToString { "${it.first}=${it.second}" }}) — sync clients again"
        }
        else null
    }

    /** `--database rocksdb` exists only in builds with the `rocksdb` feature (sc-cli `arg_enums::Database`); say so before the unit crash-loops. */
    private fun databaseUnavailable(bin: Path, env: SubstrateEnv, mode: SubstrateMode): String?
    {
        val db = if (mode == SubstrateMode.ARCHIVE) env.archiveDb else SubstrateDb.PARITYDB
        if (db != SubstrateDb.ROCKSDB) return null
        val help = try
        {
            val p = ProcessBuilder(bin.resolve(spec.binary).toString(), "--help").redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            out
        }
        catch (_: Exception)
        {
            return null
        }
        return if (help.contains("rocksdb")) null
        else "this ${spec.binary} build has no rocksdb support (`--database` lists no rocksdb) — the archive node of ${env.id} needs it"
    }

    /**
     * Pruning is stored in the database on its first start: pruned <-> archive on an existing database makes the node
     * exit with a pruning-mode error. The first Start records the mode; a different mode on an existing DB is refused.
     */
    private fun modeConflict(root: Path, state: Path, env: SubstrateEnv, mode: SubstrateMode): String?
    {
        val marker = root.resolve(".toolkit").resolve("substrate-mode")
        val chainDir = state.resolve("chains").resolve(env.chainDirId)
        val hasDb = Files.isDirectory(chainDir.resolve(SubstrateDb.PARITYDB.dirName)) || Files.isDirectory(chainDir.resolve(SubstrateDb.ROCKSDB.dirName))
        val recorded = runCatching { Files.readString(marker).trim() }.getOrNull()
        if (hasDb && recorded != null && recorded != mode.id)
        {
            return "this database was created as a $recorded node; ${mode.id} needs a new database — wipe $chainDir (the node's data) and restore / sync again"
        }
        if (recorded == null || !hasDb)
        {
            Files.writeString(marker, mode.id + "\n")
        }
        return null
    }

    private fun writeData(files: Map<Path, String>)
    {
        for ((path, text) in files)
        {
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
        }
    }

    private fun writeFiles(root: Path, files: Map<String, String>)
    {
        for ((rel, text) in files)
        {
            val path = root.resolve(rel)
            Files.createDirectories(path.parent)
            Files.writeString(path, text)
            runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxr-xr-x")) }
        }
    }
}
