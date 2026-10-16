package rpcnode.toolkit.chains.substrate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateDb
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpecs
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateSnapshotIndex
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateSnapshotResolver

class SubstrateSnapshotTest
{
    /** Rows copied from https://snapshots.polkadot.io/ (2026-10-13), including the `1.47109E+6` block formatting. */
    private val page = """
<table role="grid"><thead><tr><th><b>Chain</b></th></tr></thead><tbody>
        <tr>
          <td>paseo-rocksdb-archive</td>
          <td>1.24.1-8ae9775dc43</td>
          <td>2026-10-13T01:42:22Z</td>
          <td>1.47109E+6</td>
          <td>91.96 GB</td>
          <td>archive</td>
          <td>https://snapshots.polkadot.io/paseo-rocksdb-archive/20261013-014255</td>
        </tr>
        <tr>
          <td>polkadot-paritydb-state-blocks-pruned</td>
          <td>1.24.1-8ae9775dc43</td>
          <td>2026-10-13T18:54:43Z</td>
          <td>33417004</td>
          <td>22.06 GB</td>
          <td>256</td>
          <td>https://snapshots.polkadot.io/polkadot-paritydb-state-blocks-pruned/20261013-185609</td>
        </tr>
        <tr>
          <td>polkadot-rocksdb-archive</td>
          <td>1.24.1-8ae9775dc43</td>
          <td>2026-10-13T05:49:00Z</td>
          <td>33409163</td>
          <td>4166.34 GB</td>
          <td>archive</td>
          <td>https://snapshots.polkadot.io/polkadot-rocksdb-archive/20261013-054920</td>
        </tr>
</tbody></table>"""

    @Test
    fun parses_the_listing()
    {
        val rows = SubstrateSnapshotIndex.parse(page)
        assertEquals(3, rows.size)
        val paseo = rows.first { it.name == "paseo-rocksdb-archive" }
        assertEquals(1_471_090L, paseo.lastBlock)
        assertEquals(91_960_000_000L, paseo.sizeBytes)
        assertEquals("https://snapshots.polkadot.io/paseo-rocksdb-archive/20261013-014255", paseo.url)
        assertEquals(4_166_340_000_000L, rows.first { it.name == "polkadot-rocksdb-archive" }.sizeBytes)
        assertEquals("256", rows.first { it.name.endsWith("pruned") }.pruning)
        assertEquals(emptyList(), SubstrateSnapshotIndex.parse("<html>nothing</html>"))
    }

    @Test
    fun every_env_names_a_snapshot_of_the_listing_format()
    {
        for (env in SubstrateSpecs.POLKADOT.envs) for ((mode, name) in env.snapshotNames)
        {
            assertNotNull(name.takeIf { it.startsWith(env.chain) }, "$mode of ${env.id}")
        }
    }

    @Test
    fun sentinel_round_trips()
    {
        val url = SubstrateSnapshotResolver.sentinel(
            "https://snapshots.polkadot.io/polkadot-paritydb-state-blocks-pruned/20261013-185609", SubstrateDb.PARITYDB, "polkadot", "polkadot", "mainnet",
        )
        assertEquals(
            "substrate-files://snapshots.polkadot.io/polkadot-paritydb-state-blocks-pruned/20261013-185609?db=paritydb&chain=polkadot&net=polkadot&env=mainnet",
            url,
        )
        val ref = SubstrateSnapshotResolver.parse(url)!!
        assertEquals("https://snapshots.polkadot.io/polkadot-paritydb-state-blocks-pruned/20261013-185609", ref.baseUrl)
        assertEquals(SubstrateDb.PARITYDB, ref.db)
        assertEquals("polkadot", ref.chainDirId)
        assertEquals("mainnet", ref.env)
        assertEquals(true, SubstrateSnapshotResolver.isOfficialUrl(url))
    }

    @Test
    fun rejects_hostile_sentinels()
    {
        val base = "substrate-files://snapshots.polkadot.io/x/20261013-1"
        assertNull(SubstrateSnapshotResolver.parse("https://snapshots.polkadot.io/x"))
        assertNull(SubstrateSnapshotResolver.parse("$base?db=paritydb&chain=../etc&net=polkadot&env=mainnet"))
        assertNull(SubstrateSnapshotResolver.parse("$base?db=sqlite&chain=polkadot&net=polkadot&env=mainnet"))
        assertNull(SubstrateSnapshotResolver.parse("$base?db=paritydb&chain=polkadot&env=mainnet"))
        assertNull(SubstrateSnapshotResolver.parse("substrate-files://snapshots.polkadot.io/x/../y?db=paritydb&chain=polkadot&net=polkadot&env=mainnet"))
    }
}
