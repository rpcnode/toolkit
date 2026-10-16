package rpcnode.toolkit.chains.fuel.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import rpcnode.toolkit.chains.fuel.infrastructure.FuelChains
import rpcnode.toolkit.chains.fuel.infrastructure.FuelEnvFile
import rpcnode.toolkit.chains.fuel.infrastructure.FuelSettings
import rpcnode.toolkit.chains.fuel.infrastructure.FuelUnitBodies
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmHostBinaries
import rpcnode.toolkit.chains.nonevm.infrastructure.ToolkitArgs
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Fuel start: binaries out of the release tar.gz into `bin/`, the chain config into `<node_dir>/chain/`, a libp2p key
 * generated once, the 0600 env file (`KEYPAIR`, `RELAYER`), the unit. The Ethereum RPC URL (may embed an API key) arrives as a
 * launch arg and is stripped before the launch is recorded.
 */
class FuelNodeProcessStarter : HostNodeProcessStarter
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
        val e = FuelChains.env(env)
        val state = ToolkitArgs.path(launch.args, "state") ?: root
        val bin = when (
            val r = NonEvmHostBinaries.ensure(
                root,
                listOf(BINARY, KEYGEN),
                NonEvmHostBinaries.Source.Archive("fuel-core-linux-*.tar.gz", NonEvmHostBinaries.ArchiveKind.TAR_GZ),
                extra = listOf(WASM_EXECUTOR),
            )
        )
        {
            is NonEvmHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(r.detail)
            is NonEvmHostBinaries.Result.Ok -> r.bin
        }
        if (NonEvmHostBinaries.versionOutput(bin.resolve(BINARY)) == null)
        {
            return HostNodeStartResult.Failed("fuel-core does not run on this host (wrong CPU architecture or libc) — check `${bin.resolve(BINARY)} --version`")
        }
        val relayer = ToolkitArgs.value(launch.args, "relayer") ?: e.defaultL1Rpc
        if (!FuelEnvFile.validUrl(relayer))
        {
            return HostNodeStartResult.Failed("the Ethereum RPC URL for the relayer must be an http(s) URL without spaces or quotes")
        }
        try
        {
            Files.createDirectories(state)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            val chain = root.resolve("chain")
            Files.createDirectories(chain)
            for (name in FuelChains.CHAIN_CONFIG_FILES)
            {
                val src = root.resolve(name)
                if (Files.isRegularFile(src))
                {
                    Files.copy(src, chain.resolve(name), StandardCopyOption.REPLACE_EXISTING)
                }
                else if (!Files.isRegularFile(chain.resolve(name)))
                {
                    return HostNodeStartResult.Failed("$name (chain config) missing under $root — sync clients first")
                }
            }
            val secret = ensureKey(root, bin.resolve(KEYGEN)) ?: return HostNodeStartResult.Failed("fuel-core-keygen did not produce a libp2p key")
            writeSecret(root.resolve(FuelSettings.ENV_FILE), FuelEnvFile.render(secret, relayer))
        }
        catch (ex: Exception)
        {
            return HostNodeStartResult.Failed(ex.message ?: "prepare Fuel node failed")
        }
        val body = FuelUnitBodies.unit(
            env = e,
            nodeDir = root.toString(),
            binary = bin.resolve(BINARY).toString(),
            dbPath = state.resolve("db").toString(),
            logFile = root.resolve(FuelSettings.RELATIVE_LOG).toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = e.id,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = ToolkitArgs.withoutSecrets(launch, "relayer").copy(entry = "bin/$BINARY", logFile = FuelSettings.RELATIVE_LOG),
        )
    }

    /** The libp2p secret, generated once (`fuel-core-keygen new --key-type peering`) and kept 0600. */
    private fun ensureKey(root: Path, keygen: Path): String?
    {
        val file = root.resolve(FuelSettings.KEY_FILE)
        if (Files.isRegularFile(file))
        {
            Files.readString(file).trim().takeIf { FuelEnvFile.validSecret(it) }?.let { return it }
        }
        val p = ProcessBuilder(keygen.toString(), "new", "--key-type", "peering").redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        if (!p.waitFor(30, TimeUnit.SECONDS) || p.exitValue() != 0) return null
        val secret = FuelEnvFile.secretFromKeygen(out) ?: return null
        writeSecret(file, secret + "\n")
        return secret
    }

    private fun writeSecret(file: Path, text: String)
    {
        Files.writeString(file, text)
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }

    companion object
    {
        const val BINARY = "fuel-core"
        const val KEYGEN = "fuel-core-keygen"
        const val WASM_EXECUTOR = "fuel-core-wasm-executor.wasm"
    }
}
