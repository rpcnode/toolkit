package rpcnode.toolkit.chains.bitcore.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe

/** Local height via `<cli> -datadir=… -conf=… getblockcount`. */
class BitcoreNodeHeightProbe(
    private val spec: BitcoreChainSpec,
) : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
        withContext(Dispatchers.IO) {
            val root = Path.of(nodeDir)
            val cli = root.resolve(spec.cliEntry)
            if (!Files.exists(cli))
            {
                return@withContext null
            }
            val conf = configFile.trim().ifBlank { spec.configFile }
            try
            {
                val pb = ProcessBuilder(
                    listOf(cli.toAbsolutePath().toString()) +
                        BitcoreCli.cliArgs(nodeDir, conf, env, spec.chainArg) +
                        "getblockcount",
                )
                pb.directory(root.toFile())
                pb.redirectErrorStream(true)
                val p = pb.start()
                val out = p.inputStream.bufferedReader().readText().trim()
                val code = p.waitFor()
                if (code != 0)
                {
                    return@withContext null
                }
                out.lines().lastOrNull()?.trim()?.toLongOrNull()
            }
            catch (_: Exception)
            {
                null
            }
        }
}
