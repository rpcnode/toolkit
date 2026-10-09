package rpcnode.toolkit.chains.zcash.infrastructure.http

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rpcnode.toolkit.chains.zcash.infrastructure.ZcashCli
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe

/** Local Zcash height via `zcash-cli -datadir=… getblockcount`. */
class ZcashNodeHeightProbe : HostNodeHeightProbe
{
    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
        withContext(Dispatchers.IO) {
            val root = Path.of(nodeDir)
            val cli = root.resolve("zcash/bin/zcash-cli")
            if (!Files.exists(cli))
            {
                return@withContext null
            }
            val conf = configFile.trim().ifBlank { "zcash.conf" }
            try
            {
                val pb = ProcessBuilder(
                    listOf(cli.toAbsolutePath().toString()) +
                        ZcashCli.cliArgs(nodeDir, conf, env) +
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
