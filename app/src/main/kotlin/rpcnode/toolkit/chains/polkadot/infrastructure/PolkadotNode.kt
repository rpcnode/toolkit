package rpcnode.toolkit.chains.polkadot.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateFlags
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateMode
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpecs
import rpcnode.toolkit.chains.substrate.infrastructure.proc.SubstrateCompanion
import rpcnode.toolkit.chains.substrate.infrastructure.proc.SubstrateCompanionContext
import rpcnode.toolkit.chains.substrate.infrastructure.proc.SubstrateNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter

/** Polkadot = the shared Substrate recipe + its optional companions: the REST API (`indexer=rest-api`) or the history indexer (`indexer=history`). */
object PolkadotNode
{
    val spec = SubstrateSpecs.POLKADOT

    fun starter(): HostNodeProcessStarter =
        SubstrateNodeProcessStarter(
            spec = spec,
            flags = { env, mode, basePath -> SubstrateFlags.relayChain(spec, env, mode, basePath) },
            companion = ::companion,
            companionUnits = { listOf(PolkadotIndexer.unitName(it.id)) + PolkadotHistory.allUnits(it.id) },
        )

    fun companion(ctx: SubstrateCompanionContext): SubstrateCompanion? = when
    {
        PolkadotIndexer.enabled(ctx.indexerOption) -> restApi(ctx)
        PolkadotHistory.enabled(ctx.indexerOption) -> history(ctx)
        else -> null
    }

    private fun restApi(ctx: SubstrateCompanionContext): SubstrateCompanion
    {
        val root = ctx.nodeDir.toString()
        return SubstrateCompanion(
            units = listOf(PolkadotIndexer.companion(ctx.env, root)),
            files = mapOf(
                PolkadotIndexer.SCRIPT to PolkadotIndexer.script(
                    env = ctx.env,
                    ports = spec.ports(ctx.env),
                    nodeDir = root,
                    stateDir = "$root/.toolkit",
                    indexerDir = "$root/indexer",
                    logFile = "$root/${PolkadotIndexer.LOG}",
                ),
            ),
        )
    }

    /**
     * The history indexer: an archive node is the only source of history, so any other node type is refused here instead of
     * letting the processor fail on a pruned block hours later. Files go to the `indexer` disk role (else `<node_dir>/indexer`).
     */
    private fun history(ctx: SubstrateCompanionContext): SubstrateCompanion
    {
        require(ctx.mode == SubstrateMode.ARCHIVE) {
            "the history indexer needs an archive node (Node type: Archive): a pruned node holds only the last 256 blocks and cannot serve a backfill or a restart after downtime — choose Archive, or set the indexer to none / rest-api"
        }
        val env = ctx.env
        val ports = spec.ports(env)
        val start = PolkadotHistory.normalizeStart(env, ctx.startBlock)
        val root = ctx.nodeDir.toString()
        val indexerDir = ctx.indexerDir ?: ctx.nodeDir.resolve("indexer")
        require(indexerDir.toString().none { it.isWhitespace() }) { "indexer dir must not contain spaces" }
        val idx = indexerDir.toString()
        val prepareScript = "$root/${PolkadotHistory.PREPARE_SCRIPT}"
        val runScript = "$root/${PolkadotHistory.RUN_SCRIPT}"

        // the password directory is created here, 0700, before any script runs (the prepare script keeps it that way)
        val pwDir = ctx.nodeDir.resolve(PolkadotHistory.PW_DIR)
        Files.createDirectories(pwDir)
        runCatching { Files.setPosixFilePermissions(pwDir, PosixFilePermissions.fromString("rwx------")) }
        Files.createDirectories(ctx.nodeDir.resolve("logs"))

        return SubstrateCompanion(
            units = listOf(
                PolkadotHistory.prepareUnitName(env.id) to PolkadotHistory.prepareUnit(env, prepareScript),
                PolkadotHistory.unitName(env.id) to PolkadotHistory.processorUnit(env, root, "$runScript processor", "$root/${PolkadotHistory.PROCESSOR_LOG}"),
                PolkadotHistory.apiUnitName(env.id) to PolkadotHistory.apiUnit(env, root, "$runScript api", "$root/${PolkadotHistory.API_LOG}"),
            ),
            files = mapOf(
                PolkadotHistory.PREPARE_SCRIPT to PolkadotHistory.prepareScript(env, "$root/.toolkit", idx, "$root/${PolkadotHistory.PREPARE_LOG}"),
                PolkadotHistory.RUN_SCRIPT to PolkadotHistory.runScript(env, ports, root, idx, start),
                // written once the indexer was enabled and never removed: dropping the data is the node removal's job
                PolkadotHistory.REMOVE_HOOK to PolkadotHistory.removeHook(root, idx),
            ),
            dataFiles = PolkadotHistory.PROJECT_FILES.associate { indexerDir.resolve(PolkadotHistory.SQUID_DIR).resolve(it) to PolkadotHistory.projectFile(it) },
        )
    }
}
