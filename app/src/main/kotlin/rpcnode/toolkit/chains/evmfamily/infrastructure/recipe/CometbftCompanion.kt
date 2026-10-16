package rpcnode.toolkit.chains.evmfamily.infrastructure.recipe

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftTomlPatch
import rpcnode.toolkit.chains.cometbft.infrastructure.TomlSetting

/**
 * Helpers for the CometBFT-based consensus companions of an EVM client (Berachain `beacond`, DATA `story`): run the
 * client's own `init` once, then patch its TOML files on every Start so ports and exposure always equal the catalog.
 * The TOML patcher is the one the CometBFT family uses.
 */
object CometbftCompanion
{
    /** Runs [command]; null on success, else the last lines of its output. */
    fun run(command: List<String>, timeoutSeconds: Long = 120): String?
    {
        return try
        {
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS))
            {
                p.destroyForcibly()
                return "${command.first()} timed out after ${timeoutSeconds}s"
            }
            if (p.exitValue() == 0) null else "${Path.of(command.first()).fileName} ${command.getOrNull(1).orEmpty()} failed: ${out.takeLast(400)}"
        }
        catch (e: Exception)
        {
            e.message ?: "${command.first()} failed"
        }
    }

    /** Patches [file] with [settings] when it exists; returns true when the text changed. */
    fun patch(file: Path, settings: List<TomlSetting>): Boolean
    {
        if (!Files.isRegularFile(file)) return false
        val text = Files.readString(file)
        val patched = CometbftTomlPatch.apply(text, settings)
        if (patched == text) return false
        Files.writeString(file, patched)
        return true
    }

    fun moniker(nodeId: String): String =
        "rpcnode-" + nodeId.trim().lowercase().replace(Regex("[^a-z0-9-]"), "-").take(24)
}
