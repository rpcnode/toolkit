package rpcnode.toolkit.chains.zcash.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/** Host start for zcashd — fetch shielded params if missing, then systemd unit. */
class ZcashNodeProcessStarter : HostNodeProcessStarter
{
    override fun start(
        nodeId: String,
        network: String,
        env: String,
        nodeDir: String,
        launch: NodeLaunchSpec,
    ): HostNodeStartResult
    {
        val root = Path.of(nodeDir.trim())
        when (val params = ensureParams(root))
        {
            is ParamsResult.Ready -> Unit
            is ParamsResult.Failed -> return HostNodeStartResult.Failed(params.detail)
        }
        return HostNodeLaunchSupport.startProcess(
            nodeId = nodeId,
            network = network,
            env = env,
            nodeDir = root,
            launch = launch,
        )
    }

    private sealed interface ParamsResult
    {
        data object Ready : ParamsResult
        data class Failed(val detail: String) : ParamsResult
    }

    private fun ensureParams(nodeDir: Path): ParamsResult
    {
        val fetch = nodeDir.resolve("zcash/bin/zcash-fetch-params")
        if (!Files.isExecutable(fetch))
        {
            return ParamsResult.Ready
        }
        val paramsDir = nodeDir.resolve(".zcash-params")
        if (paramsReady(paramsDir))
        {
            return ParamsResult.Ready
        }
        return try
        {
            Files.createDirectories(paramsDir)
            val pb = ProcessBuilder(
                fetch.toAbsolutePath().toString(),
                "-paramsdir=${paramsDir.toAbsolutePath()}",
            )
            pb.directory(nodeDir.toFile())
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText().take(800)
            val code = p.waitFor()
            if (code != 0)
            {
                return ParamsResult.Failed("zcash-fetch-params exit $code: $out")
            }
            if (!paramsReady(paramsDir))
            {
                return ParamsResult.Failed("zcash-fetch-params finished but params dir still empty")
            }
            ParamsResult.Ready
        }
        catch (e: Exception)
        {
            ParamsResult.Failed(e.message ?: "zcash-fetch-params failed")
        }
    }

    private fun paramsReady(paramsDir: Path): Boolean
    {
        return Files.isRegularFile(paramsDir.resolve("sapling-spend.params")) &&
            Files.isRegularFile(paramsDir.resolve("sapling-output.params"))
    }
}
