package rpcnode.toolkit.cdn.presentation

import rpcnode.toolkit.cdn.application.status.MirrorState
import rpcnode.toolkit.cdn.application.status.MirrorStatusRow
import rpcnode.toolkit.cdn.infrastructure.http.ResumableHttpDownload

/**
 * Plain-text status table (unit tests). Live CLI uses [CdnTerminal.printStatusTable].
 */
object CdnStatusTable
{
    fun render(rows: List<MirrorStatusRow>): List<String>
    {
        if (rows.isEmpty())
        {
            return listOf("(no targets)")
        }
        val headers = listOf("TARGET", "STATE", "VERSION", "PROGRESS", "SIZE", "DOWNLOADS")
        val body = rows.map { row ->
            listOf(
                row.id,
                when (row.state)
                {
                    MirrorState.EMPTY -> "empty"
                    MirrorState.READY -> "ready"
                    MirrorState.DOWNLOADING -> "downloading"
                },
                versionLabel(row),
                progressLabel(row),
                sizeLabel(row),
                downloadsLabel(row),
            )
        }
        val widths = headers.indices.map { i ->
            (listOf(headers[i]) + body.map { it[i] }).maxOf { it.length }
        }
        fun line(cols: List<String>): String =
            cols.mapIndexed { i, c -> c.padEnd(widths[i]) }.joinToString("  ")
        val out = mutableListOf(line(headers), line(widths.map { "-".repeat(it) }))
        body.forEach { out += line(it) }
        return out
    }

    private fun versionLabel(row: MirrorStatusRow): String
    {
        val fetch = row.fetchingVersion
        val have = row.onDiskVersion
        return when (row.state)
        {
            MirrorState.DOWNLOADING ->
                when
                {
                    fetch != null && have != null && fetch != have -> "$have → $fetch"
                    fetch != null -> fetch
                    have != null -> have
                    else -> "—"
                }
            MirrorState.READY -> have ?: "—"
            MirrorState.EMPTY -> "—"
        }
    }

    private fun progressLabel(row: MirrorStatusRow): String
    {
        if (row.state != MirrorState.DOWNLOADING)
        {
            return "—"
        }
        val have = row.haveBytes
        val total = row.totalBytes
        val pct = row.progressPct
        return when
        {
            have != null && total != null && total > 0 && pct != null ->
                "$pct% (${ResumableHttpDownload.formatBytes(have)} / ${ResumableHttpDownload.formatBytes(total)})"
            have != null ->
                "${ResumableHttpDownload.formatBytes(have)} so far"
            else -> "starting…"
        }
    }

    private fun sizeLabel(row: MirrorStatusRow): String
    {
        val n = when (row.state)
        {
            MirrorState.READY -> row.totalBytes ?: row.haveBytes
            MirrorState.DOWNLOADING -> row.totalBytes
            MirrorState.EMPTY -> null
        } ?: return "—"
        return ResumableHttpDownload.formatBytes(n)
    }

    private fun downloadsLabel(row: MirrorStatusRow): String =
        if (row.downloadCount > 0L) row.downloadCount.toString() else "—"
}
