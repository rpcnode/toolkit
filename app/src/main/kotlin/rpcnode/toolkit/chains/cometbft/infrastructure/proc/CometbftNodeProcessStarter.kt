package rpcnode.toolkit.chains.cometbft.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftHostBinaries
import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftProfile
import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftSettings
import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftTomlPatch
import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftUnitBodies
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/**
 * Start of a CometBFT chain under [nodeDir]: install the binary, `init` once, copy the genesis, patch `config.toml` /
 * `app.toml` (every Start, so ports and limits always equal the network.yml bindings), install the unit.
 */
class CometbftNodeProcessStarter(private val profile: CometbftProfile) : HostNodeProcessStarter
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
        val e = profile.env(env)
        val home = launch.args.firstOrNull { it.startsWith("--toolkit-home=") }
            ?.removePrefix("--toolkit-home=")?.trim()?.takeIf { it.startsWith("/") && !it.contains("..") }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
            ?: root.resolve("home")
        if (root.toString().any { it.isWhitespace() } || home.toString().any { it.isWhitespace() })
        {
            return HostNodeStartResult.Failed("node_dir must not contain spaces")
        }
        val bin = when (val r = CometbftHostBinaries.ensure(root, profile))
        {
            is CometbftHostBinaries.Result.Failed -> return HostNodeStartResult.Failed(r.detail)
            is CometbftHostBinaries.Result.Ok -> r.binary
        }
        try
        {
            Files.createDirectories(home)
            Files.createDirectories(root.resolve("logs"))
            Files.createDirectories(root.resolve(".toolkit"))
            val configToml = home.resolve("config").resolve("config.toml")
            if (!Files.isRegularFile(configToml))
            {
                val moniker = "rpcnode-" + nodeId.trim().lowercase().replace(Regex("[^a-z0-9-]"), "-").take(24)
                val init = ProcessBuilder(bin.toString(), "init", moniker, "--chain-id", e.chainId, "--home", home.toString())
                    .redirectErrorStream(true)
                    .also { it.environment()["LD_LIBRARY_PATH"] = bin.parent.toString() }
                    .start()
                val out = init.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                if (!init.waitFor(120, TimeUnit.SECONDS) || init.exitValue() != 0)
                {
                    return HostNodeStartResult.Failed("${profile.binary} init failed: ${out.takeLast(400)}")
                }
                // a non-validator RPC node must not carry a validator key (double-sign risk if it is ever copied)
                runCatching { Files.deleteIfExists(home.resolve("config").resolve("priv_validator_key.json")) }
                val genesis = root.resolve("genesis.json")
                if (profile.shipsGenesis && Files.isRegularFile(genesis))
                {
                    Files.copy(genesis, home.resolve("config").resolve("genesis.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val settings = CometbftSettings.forEnv(profile, e)
            patch(home.resolve("config").resolve(CometbftSettings.CONFIG), settings.filter { it.file == CometbftSettings.CONFIG })
            patch(home.resolve("config").resolve(CometbftSettings.APP), settings.filter { it.file == CometbftSettings.APP })
        }
        catch (ex: Exception)
        {
            return HostNodeStartResult.Failed(ex.message ?: "prepare ${profile.network.value} home failed")
        }
        val args = CometbftUnitBodies.startArgs(profile, e, home.toString())
        val body = CometbftUnitBodies.unit(
            profile = profile,
            env = e,
            nodeDir = root.toString(),
            execStart = (listOf(bin.toString()) + args).joinToString(" "),
            logFile = root.resolve(CometbftUnitBodies.relativeLog(profile)).toString(),
        )
        return HostNodeLaunchSupport.installCustomUnits(
            nodeId = nodeId,
            network = network,
            env = e.id,
            nodeDir = root,
            primaryBody = body,
            companions = emptyList(),
            launch = launch.copy(entry = "bin/${profile.binary}", logFile = CometbftUnitBodies.relativeLog(profile)),
        )
    }

    private fun patch(file: Path, settings: List<rpcnode.toolkit.chains.cometbft.infrastructure.TomlSetting>)
    {
        if (!Files.isRegularFile(file)) return
        val text = Files.readString(file)
        val patched = CometbftTomlPatch.apply(text, settings)
        if (patched != text) Files.writeString(file, patched)
    }
}
