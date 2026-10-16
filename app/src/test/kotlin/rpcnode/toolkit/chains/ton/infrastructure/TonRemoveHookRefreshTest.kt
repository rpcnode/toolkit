package rpcnode.toolkit.chains.ton.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TonRemoveHookRefreshTest
{
    private fun nodeDirWith(hook: String?): Path
    {
        val dir = Files.createTempDirectory("ton-node")
        if (hook != null)
        {
            val f = dir.resolve(TonUnitBodies.REMOVE_HOOK_SCRIPT)
            Files.createDirectories(f.parent)
            Files.writeString(f, hook)
        }
        return dir
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("ton/$name")) { "missing $name" }.readBytes().toString(Charsets.UTF_8)

    @Test
    fun a_hook_written_by_an_older_agent_is_rewritten_with_its_own_paths_and_stays_executable()
    {
        val dir = nodeDirWith(resource("old-remove-hook-v1.sh"))
        assertTrue(TonRemoveHookRefresh.refresh(dir))
        val hook = dir.resolve(TonUnitBodies.REMOVE_HOOK_SCRIPT)
        val text = Files.readString(hook)
        assertTrue(text.contains("rpcnode-ton-hook-version: ${TonUnitBodies.REMOVE_HOOK_VERSION}\n"))
        assertTrue(text.contains("PWDIR='/mnt/raid0/rpcnode/ton/testnet/blockchain/.toolkit/ton-indexer'"), "the state dir of the old hook is kept")
        assertTrue(text.contains("IDX='/mnt/raid0/rpcnode/ton/testnet/indexer'"))
        assertTrue(text.contains("remove_mytonctrl"), "the old hook knew nothing about MyTonCtrl")
        assertTrue(text.contains("/opt/virtualenv/ton_http_api"))
        assertFalse(text.contains("{{"))
        assertTrue(Files.isExecutable(hook))
        assertFalse(Files.exists(hook.resolveSibling("${hook.fileName}.tmp")), "no temp file left behind")
    }

    @Test
    fun a_current_hook_is_left_alone()
    {
        val current = TonUnitBodies.removeHook("/n/.toolkit", "/n/indexer")
        val dir = nodeDirWith(current)
        assertFalse(TonRemoveHookRefresh.refresh(dir))
        assertEquals(current, Files.readString(dir.resolve(TonUnitBodies.REMOVE_HOOK_SCRIPT)))
    }

    @Test
    fun a_second_refresh_changes_nothing()
    {
        val dir = nodeDirWith(resource("old-remove-hook-v1.sh"))
        assertTrue(TonRemoveHookRefresh.refresh(dir))
        val once = Files.readString(dir.resolve(TonUnitBodies.REMOVE_HOOK_SCRIPT))
        assertFalse(TonRemoveHookRefresh.refresh(dir))
        assertEquals(once, Files.readString(dir.resolve(TonUnitBodies.REMOVE_HOOK_SCRIPT)))
    }

    @Test
    fun scripts_that_are_not_ton_hooks_and_missing_hooks_are_never_touched()
    {
        val other = "#!/bin/bash\n# cardano db-sync clean-up\nPWDIR='/x/.toolkit/ton-indexer'\nIDX='/y'\n"
        val dir = nodeDirWith(other)
        assertFalse(TonRemoveHookRefresh.refresh(dir))
        assertEquals(other, Files.readString(dir.resolve(TonUnitBodies.REMOVE_HOOK_SCRIPT)))
        assertFalse(TonRemoveHookRefresh.refresh(nodeDirWith(null)))
        // a TON hook without the two paths cannot be rebuilt: it is left as it is
        val odd = "#!/bin/bash\n# drops what rpcnode-ton-indexer.sh created\nexit 0\n"
        val oddDir = nodeDirWith(odd)
        assertFalse(TonRemoveHookRefresh.refresh(oddDir))
        assertEquals(odd, Files.readString(oddDir.resolve(TonUnitBodies.REMOVE_HOOK_SCRIPT)))
    }

    @Test
    fun the_refreshed_hook_is_valid_bash()
    {
        val dir = nodeDirWith(resource("old-remove-hook-v1.sh"))
        TonRemoveHookRefresh.refresh(dir)
        val p = ProcessBuilder("bash", "-n", dir.resolve(TonUnitBodies.REMOVE_HOOK_SCRIPT).toString()).redirectErrorStream(true).start()
        assertEquals(0, p.waitFor(), p.inputStream.bufferedReader().readText())
    }
}
