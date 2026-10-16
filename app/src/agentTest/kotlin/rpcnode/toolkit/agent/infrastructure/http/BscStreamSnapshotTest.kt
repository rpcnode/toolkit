package rpcnode.toolkit.agent.infrastructure.http

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BscStreamSnapshotTest
{
    private val readme = """
        | Pruned Snapshot | [mainnet-geth-pbss-20261009-pruneancient](dist/mainnet-geth-pbss-20261009-pruneancient.csv) | **~1.9TB** |
        | Pruned Snapshot | [testnet-geth-pbss-20260917-pruneancient](dist/testnet-geth-pbss-20260917-pruneancient.csv) | **~180GB** |
        - [mainnet-geth-pbss-20260805](dist/mainnet-geth-pbss-20260805.csv)
    """.trimIndent()

    private val prunedCsv = """
        filename,URL,md5,size
        mainnet-geth-pbss-base-126063687.tar.lz4,https://r2.example/mainnet-geth-pbss-base-126063687.tar.lz4,8c567d8f79f9cccdf7914e19f2833b74,1899.49GB
        mainnet-geth-pbss-blocks-pruneancient-125973687.tar.lz4,https://r2.example/b.tar.lz4,7a9735164fd73cbc41a0a5147418f792,0.00GB
    """.trimIndent()

    @Test
    fun latest_name_is_first_match_in_readme()
    {
        assertEquals("mainnet-geth-pbss-20261009", BscStreamSnapshot.latestName(readme, "mainnet-geth-pbss"))
        assertEquals("testnet-geth-pbss-20260917", BscStreamSnapshot.latestName(readme, "testnet-geth-pbss"))
    }

    @Test
    fun csv_rows_parse_with_gib_sizes()
    {
        val parts = BscStreamSnapshot.parseCsv(prunedCsv)
        assertEquals(2, parts.size)
        assertEquals("mainnet-geth-pbss-base-126063687.tar.lz4", parts[0].filename)
        assertEquals("8c567d8f79f9cccdf7914e19f2833b74", parts[0].md5)
        // Mirror Content-Length for this part is 2039560866012 — the CSV size is GiB.
        val size = parts[0].sizeBytes!!
        assertTrue(kotlin.math.abs(size - 2_039_560_866_012L) < 10L * (1L shl 20), "size=$size")
        assertEquals(0L, parts[1].sizeBytes)
    }

    @Test
    fun pruned_plan_reads_pruneancient_csv_full_reads_plain()
    {
        val asked = mutableListOf<String>()
        val stream = BscStreamSnapshot(fetchText = { url ->
            asked += url
            if (url.endsWith("README.md")) readme else prunedCsv
        })
        val pruned = stream.resolvePlan("mainnet", "pruned")
        assertEquals("mainnet-geth-pbss-20261009-pruneancient", pruned.csvName)
        assertTrue(asked.last().endsWith("/dist/mainnet-geth-pbss-20261009-pruneancient.csv"))
        val full = stream.resolvePlan("mainnet", "full")
        assertEquals("mainnet-geth-pbss-20261009", full.csvName)
        assertTrue(asked.last().endsWith("/dist/mainnet-geth-pbss-20261009.csv"))
    }

    @Test
    fun unresolvable_readme_fails()
    {
        val stream = BscStreamSnapshot(fetchText = { "nothing here" })
        assertFailsWith<IllegalStateException> { stream.resolvePlan("mainnet", "pruned") }
    }

    @Test
    fun done_record_is_tied_to_the_snapshot_name()
    {
        val dir = Files.createTempDirectory("bsc-stream")
        val stream = BscStreamSnapshot(fetchText = { url -> if (url.endsWith("README.md")) readme else prunedCsv })
        val plan = stream.resolvePlan("mainnet", "pruned")
        Files.createDirectories(dir.resolve("geth/chaindata"))
        stream.beginFresh(dir, plan)
        assertFalse(Files.exists(dir.resolve("geth")), "fresh start drops the old geth dir")
        assertTrue(stream.donePartsFor(dir, plan).isEmpty())
        stream.markPartDone(dir, plan.parts[0])
        assertEquals(setOf(plan.parts[0].filename), stream.donePartsFor(dir, plan))
        val other = plan.copy(csvName = "mainnet-geth-pbss-20261109-pruneancient")
        assertTrue(stream.donePartsFor(dir, other).isEmpty(), "a newer snapshot must not reuse old parts")
    }

    @Test
    fun tar_command_strips_prefix_to_geth()
    {
        val cmd = BscStreamSnapshot.tarCommand(java.nio.file.Path.of("/data/bsc"))
        assertEquals(listOf("tar", "-I", "lz4", "-x", "-f", "-", "-C", "/data/bsc"), cmd.take(8))
        assertTrue("--transform" in cmd)
        assertEquals("*geth/*", cmd.last())
    }
}
