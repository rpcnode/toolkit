package rpcnode.toolkit.chains.evmfamily.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmHostBinaries
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmHostFacts
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmJwt
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmLaunchOptions
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Start of an EVM-family node under node_dir: install the binaries, create the JWT, run the recipe's one-shot
 * preparation (init, config patch), then install the units (primary + consensus companion) through systemd.
 */
class EvmNodeProcessStarter(private val profile: EvmProfile) : HostNodeProcessStarter
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
        val options = EvmLaunchOptions.parse(launch.args)
        val e = profile.env(env)
        val bins = when (val r = EvmHostBinaries.ensure(root, profile.recipe.binaries(e)))
        {
            is EvmHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(r.detail)
            is EvmHostBinaries.Result.Ok -> r.bins
        }
        val dirs = profile.diskRoles.associateWith { role ->
            options.dirs[role]?.let { Path.of(it).toAbsolutePath().normalize() } ?: root
        }
        if (dirs.values.any { it.toString().any { c -> c.isWhitespace() } })
        {
            return HostNodeStartResult.Failed("disk role directories must not contain spaces")
        }
        val ctx = try
        {
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            dirs.values.forEach { Files.createDirectories(it) }
            EvmStartContext(
                profile = profile,
                env = e,
                nodeId = nodeId,
                root = root,
                bins = bins,
                dirs = dirs,
                options = options,
                jwt = if (profile.recipe.usesJwt) EvmJwt.ensure(root) else null,
                memTotalGiB = EvmHostFacts.memTotalGiB(),
            )
        }
        catch (ex: Exception)
        {
            return HostNodeStartResult.Failed(ex.message ?: "prepare ${profile.network.value} directories failed")
        }
        profile.recipe.prepare(ctx)?.let { return HostNodeStartResult.Failed(it) }
        val units = try
        {
            profile.recipe.units(ctx)
        }
        catch (ex: Exception)
        {
            return HostNodeStartResult.Failed(ex.message ?: "render ${profile.network.value} units failed")
        }
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = e.id,
            nodeDir = root,
            primaryBody = units.primary,
            companions = units.companions,
            launch = launch.copy(
                entry = "bin/${profile.recipe.binaries(e).first().name}",
                logFile = profile.relativeLog(),
            ),
            startCompanionsFirst = units.startCompanionsFirst,
        )
    }
}
