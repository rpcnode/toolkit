package rpcnode.toolkit.chains.stacks.infrastructure.proc

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmHostBinaries
import rpcnode.toolkit.chains.nonevm.infrastructure.ToolkitArgs
import rpcnode.toolkit.chains.stacks.infrastructure.StacksBurnchain
import rpcnode.toolkit.chains.stacks.infrastructure.StacksChains
import rpcnode.toolkit.chains.stacks.infrastructure.StacksConfigToml
import rpcnode.toolkit.chains.stacks.infrastructure.StacksSettings
import rpcnode.toolkit.chains.stacks.infrastructure.StacksUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Stacks start: `stacks-node` out of the release zip into `bin/`, `stacks.toml` (0600) from the tag's sample follower config,
 * unit. Mainnet refuses to start when the Bitcoin node of the `burnchain_*` options cannot be reached.
 */
class StacksNodeProcessStarter(
    private val reachable: (host: String, port: Int) -> Boolean = ::tcpReachable,
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
        val e = StacksChains.env(env)
        val state = ToolkitArgs.path(launch.args, "state") ?: root
        val bin = when (val r = NonEvmHostBinaries.ensure(root, listOf(BINARY), NonEvmHostBinaries.Source.Archive("stacks-core-linux-*.zip", NonEvmHostBinaries.ArchiveKind.ZIP)))
        {
            is NonEvmHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(r.detail)
            is NonEvmHostBinaries.Result.Ok -> r.bin.resolve(BINARY)
        }
        if (NonEvmHostBinaries.versionOutput(bin) == null)
        {
            return HostNodeStartResult.Failed("stacks-node does not run on this host (wrong CPU architecture or libc) — check `$bin --version`")
        }
        val burn = if (e.local) burnchainOf(launch.args) else null
        if (burn != null)
        {
            StacksConfigToml.validate(burn)?.let { return HostNodeStartResult.Failed(it) }
            for (port in listOf(burn.rpcPort, burn.p2pPort))
            {
                if (!reachable(burn.host, port))
                {
                    return HostNodeStartResult.Failed(
                        "Bitcoin node ${burn.host}:$port is not reachable — Stacks mainnet needs a Bitcoin Core node on its " +
                            "P2P (${burn.p2pPort}) and RPC (${burn.rpcPort}) ports with rpcuser/rpcpassword (it cannot use the .cookie)",
                    )
                }
            }
        }
        val template = Files.newDirectoryStream(root, "*${e.sampleConf}").use { s -> s.firstOrNull { Files.isRegularFile(it) } }
            ?: return HostNodeStartResult.Failed("${e.sampleConf} missing under $root — sync clients first")
        val config = root.resolve(StacksSettings.CONFIG_FILE)
        try
        {
            Files.createDirectories(state)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            val text = StacksConfigToml.render(Files.readString(template), e, state.toString(), burn)
            Files.writeString(config, text)
            runCatching { Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("rw-------")) }
        }
        catch (ex: Exception)
        {
            return HostNodeStartResult.Failed(ex.message ?: "write stacks.toml failed")
        }
        val body = StacksUnitBodies.unit(e, root.toString(), bin.toString(), config.toString(), root.resolve(StacksSettings.RELATIVE_LOG).toString())
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = e.id,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = ToolkitArgs.withoutSecrets(launch, "burn-pass").copy(entry = "bin/$BINARY", logFile = StacksSettings.RELATIVE_LOG),
        )
    }

    private fun burnchainOf(args: List<String>): StacksBurnchain = StacksBurnchain(
        host = ToolkitArgs.value(args, "burn-host") ?: StacksBurnchain.DEFAULT_HOST,
        rpcPort = ToolkitArgs.value(args, "burn-rpc-port")?.toIntOrNull() ?: StacksBurnchain.DEFAULT_RPC_PORT,
        p2pPort = ToolkitArgs.value(args, "burn-p2p-port")?.toIntOrNull() ?: StacksBurnchain.DEFAULT_P2P_PORT,
        username = ToolkitArgs.value(args, "burn-user").orEmpty(),
        password = ToolkitArgs.value(args, "burn-pass").orEmpty(),
    )

    companion object
    {
        const val BINARY = "stacks-node"

        fun tcpReachable(host: String, port: Int): Boolean =
            runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 3_000) } }.isSuccess
    }
}
