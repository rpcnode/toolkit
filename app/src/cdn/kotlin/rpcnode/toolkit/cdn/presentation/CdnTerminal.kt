package rpcnode.toolkit.cdn.presentation

import com.github.ajalt.mordant.input.interactiveSelectList
import com.github.ajalt.mordant.rendering.TextColors
import com.github.ajalt.mordant.rendering.TextStyles
import com.github.ajalt.mordant.table.table
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.mordant.widgets.SelectList
import rpcnode.toolkit.cdn.application.status.MirrorState
import rpcnode.toolkit.cdn.application.status.MirrorStatusRow
import rpcnode.toolkit.cdn.infrastructure.http.ResumableHttpDownload

/** Mordant-backed terminal helpers for the CDN CLI. */
object CdnTerminal
{
    fun create(): Terminal = Terminal()

    fun pickIndex(
        terminal: Terminal,
        title: String,
        items: List<String>,
        initial: Int = 0,
    ): Int?
    {
        if (items.isEmpty())
        {
            return null
        }
        val highlight = initial.coerceIn(0, items.lastIndex)
        val selected = terminal.interactiveSelectList {
            title(title)
            items.forEachIndexed { i, label ->
                addEntry(title = label, selected = i == highlight)
            }
        } ?: return null
        val idx = items.indexOf(selected)
        return idx.takeIf { it >= 0 }
    }

    fun waitEnter(terminal: Terminal, message: String = "Press Enter to continue…")
    {
        terminal.prompt(message, default = "")
    }

    fun printStatusTable(terminal: Terminal, rows: List<MirrorStatusRow>)
    {
        if (rows.isEmpty())
        {
            terminal.println(TextColors.gray("(no targets)"))
            return
        }
        terminal.println(
            table {
                header {
                    row("TARGET", "STATE", "VERSION", "PROGRESS", "SIZE", "DOWNLOADS")
                }
                body {
                    for (row in rows)
                    {
                        row(
                            row.id,
                            stateCell(row),
                            versionLabel(row),
                            progressLabel(row),
                            sizeLabel(row),
                            downloadsLabel(row),
                        )
                    }
                }
            },
        )
    }

    private fun stateCell(row: MirrorStatusRow): String = when (row.state)
    {
        MirrorState.EMPTY -> TextColors.gray("empty")
        MirrorState.READY -> TextColors.green("ready")
        MirrorState.DOWNLOADING -> TextColors.yellow("downloading")
    }

    private fun versionLabel(row: MirrorStatusRow): String
    {
        return when (row.state)
        {
            MirrorState.DOWNLOADING ->
            {
                val fetch = row.fetchingVersion
                val have = row.onDiskVersion
                when
                {
                    fetch != null && have != null && fetch != have ->
                        "$have ${TextStyles.dim("→")} $fetch"
                    fetch != null -> fetch
                    have != null -> have
                    else -> TextStyles.dim("—")
                }
            }
            MirrorState.READY -> row.onDiskVersion ?: TextStyles.dim("—")
            MirrorState.EMPTY -> TextStyles.dim("—")
        }
    }

    private fun progressLabel(row: MirrorStatusRow): String
    {
        if (row.state != MirrorState.DOWNLOADING)
        {
            return TextStyles.dim("—")
        }
        val have = row.haveBytes
        val total = row.totalBytes
        val pct = row.progressPct
        return when
        {
            have != null && total != null && total > 0 && pct != null ->
                TextColors.cyan("$pct%") +
                    " (${ResumableHttpDownload.formatBytes(have)} / ${ResumableHttpDownload.formatBytes(total)})"
            have != null ->
                "${ResumableHttpDownload.formatBytes(have)} so far"
            else -> TextStyles.dim("starting…")
        }
    }

    private fun sizeLabel(row: MirrorStatusRow): String
    {
        val n = when (row.state)
        {
            MirrorState.READY -> row.totalBytes ?: row.haveBytes
            MirrorState.DOWNLOADING -> row.totalBytes
            MirrorState.EMPTY -> null
        } ?: return TextStyles.dim("—")
        return ResumableHttpDownload.formatBytes(n)
    }

    private fun downloadsLabel(row: MirrorStatusRow): String =
        if (row.downloadCount > 0L) row.downloadCount.toString() else TextStyles.dim("—")
}
