package rpcnode.toolkit.chains.ton.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The remove hook (`bin/rpcnode-on-remove.sh`) is written when the node is started, so a node installed by an older agent keeps
 * the hook of that time: one that cleans up less (the first version only dropped the indexer database and left MyTonCtrl,
 * `ton_http_api` and its files behind). Before a node is removed the agent calls [refresh]: a TON hook that is older than
 * [TonUnitBodies.REMOVE_HOOK_VERSION] is rewritten from the current template, taking the two paths it needs from the old file.
 */
object TonRemoveHookRefresh
{
    private val PW_DIR = Regex("(?m)^PWDIR='([^']+)'\\s*$")
    private val IDX_DIR = Regex("(?m)^IDX='([^']+)'\\s*$")
    private const val STATE_SUFFIX = "/ton-indexer"

    /** True when the hook under [nodeDir] was rewritten. Anything that is not a recognisable TON hook is left exactly as it is. */
    fun refresh(nodeDir: Path): Boolean
    {
        val hook = nodeDir.resolve(TonUnitBodies.REMOVE_HOOK_SCRIPT)
        if (!Files.isRegularFile(hook)) return false
        val text = runCatching { Files.readString(hook) }.getOrNull() ?: return false
        if (!text.contains("rpcnode-ton-indexer.sh")) return false
        if (text.contains("rpcnode-ton-hook-version: ${TonUnitBodies.REMOVE_HOOK_VERSION}\n")) return false
        val pwDir = PW_DIR.find(text)?.groupValues?.get(1) ?: return false
        val idxDir = IDX_DIR.find(text)?.groupValues?.get(1) ?: return false
        if (!pwDir.endsWith(STATE_SUFFIX)) return false
        val stateDir = pwDir.removeSuffix(STATE_SUFFIX)
        return runCatching {
            val tmp = hook.resolveSibling("${hook.fileName}.tmp")
            Files.writeString(tmp, TonUnitBodies.removeHook(stateDir, idxDir))
            tmp.toFile().setExecutable(true, false)
            Files.move(tmp, hook, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        }.getOrDefault(false)
    }
}
