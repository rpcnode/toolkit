package rpcnode.toolkit.cdn.presentation

import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.danger
import com.github.ajalt.mordant.terminal.success
import com.github.ajalt.mordant.terminal.warning
import java.nio.file.Path
import rpcnode.toolkit.cdn.application.status.MirrorStatusReader
import rpcnode.toolkit.cdn.application.sync.SnapshotTarget
import rpcnode.toolkit.cdn.application.targets.SnapshotTargetStore
import rpcnode.toolkit.cdn.infrastructure.catalog.EmbeddedMirrorCatalog
import rpcnode.toolkit.cdn.infrastructure.filesystem.DiskMirrorStatusReader

/**
 * Interactive menu via Mordant (arrow keys / Enter). No typing network names.
 */
object CdnMenu
{
    private val mainActions = listOf(
        "Add network/env",
        "Delete",
        "Status",
        "Change download directory",
        "Quit",
    )

    fun run(
        store: SnapshotTargetStore,
        catalog: EmbeddedMirrorCatalog = EmbeddedMirrorCatalog(),
        envFile: Path,
        snapshotDir: String?,
        statusReaderFactory: (Path) -> MirrorStatusReader = { DiskMirrorStatusReader(it) },
        terminal: Terminal = CdnTerminal.create(),
        pick: ((title: String, items: List<String>, initial: Int) -> Int?)? = null,
        waitContinue: (() -> Unit)? = null,
        pickSnapshotDir: (() -> Path?)? = null,
        onSnapshotDirChanged: ((Path) -> Unit)? = null,
    )
    {
        val choose = pick ?: { title, items, initial ->
            CdnTerminal.pickIndex(terminal, title, items, initial)
        }
        val pause = waitContinue ?: { CdnTerminal.waitEnter(terminal) }
        var downloadDir = snapshotDir
        while (true)
        {
            val current = store.list()
            terminal.println()
            terminal.println(TextStyles.bold(TextColors.brightCyan("rpcnode-cdn ${CdnConfig.version()}")))
            if (downloadDir.isNullOrBlank())
            {
                terminal.println(TextColors.yellow("  download dir: (not set — choose “Change download directory”)"))
            }
            else
            {
                val dir = downloadDir
                terminal.println("  download dir: ${TextColors.brightWhite(dir)}")
                terminal.println(TextStyles.dim("                 → $dir/snapshots/…"))
            }
            if (current.isEmpty())
            {
                terminal.println(TextStyles.dim("  targets: (none)"))
            }
            else
            {
                terminal.println("  targets:")
                current.forEach { terminal.println("    ${TextColors.brightWhite("•")} ${it.id}") }
            }
            terminal.println()
            val action = choose("Select action", mainActions, 0) ?: return
            when (action)
            {
                0 ->
                {
                    if (downloadDir.isNullOrBlank())
                    {
                        terminal.danger("set download directory first")
                        continue
                    }
                    addTarget(store, catalog, choose, terminal)
                }
                1 -> removeTarget(store, current, choose, terminal)
                2 ->
                {
                    val dir = downloadDir
                    if (dir.isNullOrBlank())
                    {
                        terminal.danger("set download directory first")
                        continue
                    }
                    showStatus(store, statusReaderFactory(Path.of(dir)), terminal)
                    pause()
                }
                3 ->
                {
                    val chosen = (pickSnapshotDir ?: {
                        CdnSnapshotDirPicker.pick(
                            terminal = terminal,
                            pick = choose,
                            current = downloadDir?.let { Path.of(it) },
                        )
                    })() ?: continue
                    CdnSnapshotDirPicker.saveToEnvFile(envFile, chosen)
                    onSnapshotDirChanged?.invoke(chosen)
                    downloadDir = chosen.toString()
                    terminal.success("saved SNAPSHOT_CDN_DIR=$downloadDir")
                    terminal.warning("restart sync if running: sudo systemctl restart rpcnode-cdn")
                }
                4 -> return
                else -> terminal.danger("unknown choice")
            }
        }
    }

    fun printStatus(
        store: SnapshotTargetStore,
        statusReader: MirrorStatusReader,
        terminal: Terminal = CdnTerminal.create(),
    )
    {
        showStatus(store, statusReader, terminal)
    }

    private fun showStatus(
        store: SnapshotTargetStore,
        statusReader: MirrorStatusReader?,
        terminal: Terminal,
    )
    {
        if (statusReader == null)
        {
            terminal.danger("status unavailable (no snapshot dir)")
            return
        }
        terminal.println()
        CdnTerminal.printStatusTable(terminal, statusReader.status(store.list()))
        terminal.println()
    }

    private fun addTarget(
        store: SnapshotTargetStore,
        catalog: EmbeddedMirrorCatalog,
        choose: (String, List<String>, Int) -> Int?,
        terminal: Terminal,
    )
    {
        val networks = catalog.networks()
        if (networks.isEmpty())
        {
            terminal.danger("no known networks in the shipped mirror catalog")
            return
        }
        val ni = choose("Network", networks, 0) ?: return
        val network = networks[ni]
        val envs = catalog.envs(network)
        if (envs.isEmpty())
        {
            terminal.danger("no envs for $network")
            return
        }
        val ei = choose("Env ($network)", envs, 0) ?: return
        val env = envs[ei]
        val types = catalog.types(network, env)
        if (types.isEmpty())
        {
            terminal.danger("no snapshot types for $network/$env")
            return
        }
        val defaultType = types.indexOf("full").coerceAtLeast(0)
        val labels = types.mapIndexed { i, label ->
            if (i == defaultType) "$label  (default)" else label
        }
        val ti = choose("Type ($network/$env)", labels, defaultType) ?: return
        val type = types[ti]
        val target = SnapshotTarget(network, env, type)
        if (store.list().any { it.id == target.id })
        {
            terminal.warning("already listed: ${target.id}")
            return
        }
        store.add(target)
        terminal.success("added ${target.id}")
    }

    private fun removeTarget(
        store: SnapshotTargetStore,
        current: List<SnapshotTarget>,
        choose: (String, List<String>, Int) -> Int?,
        terminal: Terminal,
    )
    {
        if (current.isEmpty())
        {
            terminal.warning("nothing to delete")
            return
        }
        val i = choose("Delete target", current.map { it.id }, 0) ?: return
        val target = current[i]
        if (store.remove(target.id))
        {
            terminal.success("removed ${target.id}")
        }
        else
        {
            terminal.danger("not found: ${target.id}")
        }
    }
}
