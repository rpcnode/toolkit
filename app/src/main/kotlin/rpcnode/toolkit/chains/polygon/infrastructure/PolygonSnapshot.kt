package rpcnode.toolkit.chains.polygon.infrastructure

import java.nio.file.Path
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotPaths
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotScripts
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.chains.evmfamily.infrastructure.ScriptedSnapshot
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.EvmSnapshotSentinelResolver
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver

/**
 * Polygon needs a snapshot of BOTH clients: Heimdall v2 cannot sync from its genesis (the public peers keep only about the last
 * three weeks of blocks, the genesis of amoy is at 8 788 501), and Bor from block 0 takes days. Polygon's own snapshots are
 * community-run; this one restores the snapshots of PublicNode (Allnodes), whose newest file names are read from
 * `publicnode.com/snapshots` at restore time (the file name carries the block heights and changes about daily).
 *
 * Heimdall is the pruned file. Bor is the `base` (blocks 0..B, the chain freezer) plus `part` (B+1..tip and the state) files, NOT the
 * `pruned` one: its chain freezer is empty and the key-value store lacks block 1, which Bor 2.10.2 and 2.10.3 refuse with
 * "ancient chain segments already extracted" (reproduced on both networks' archives; base + part starts).
 *
 * Heimdall first (`data/application.db/…` into the Heimdall home), then Bor (`bor/chaindata/…` into `<datadir>/data`).
 * The Heimdall home and the Bor role directory come from the node's disk layout as `dir_heimdall` / `dir_bor` parameters of the
 * sentinel URL (added by `StartNodeSnapshotUseCase`); without them the defaults of the node start apply
 * (`<bor dir>/datadir`, `<parent of bor dir>/heimdall`).
 */
object PolygonSnapshot : ScriptedSnapshot
{
    const val PAGE_URL = "https://www.publicnode.com/snapshots"
    const val KIND = "full"

    override val snapshotNetwork: String = "polygon"

    /** File-name prefixes of the PublicNode families (`<prefix>-<heights>.tar.lz4`) per env; the Bor files are unpacked in order. */
    data class Families(val heimdall: String, val bor: List<String>)

    fun families(env: String): Families? = when (env)
    {
        "amoy" -> Families("polygon-amoy-heimdall-pruned", listOf("polygon-amoy-bor-base", "polygon-amoy-bor-part"))
        "mainnet" -> Families("polygon-heimdall-pruned", listOf("polygon-bor-base", "polygon-bor-part"))
        else -> null
    }

    /** The panel side: the sentinel `evm-snapshot://polygon/<env>/pruned`. */
    fun resolver(): SnapshotResolver =
        EvmSnapshotSentinelResolver("polygon", mapOf("mainnet" to setOf(KIND), "amoy" to setOf(KIND)), KIND)

    override fun snapshotBinaries(envId: String): List<EvmBinary> = emptyList()

    override fun snapshotScript(ref: EvmSnapshotRef, paths: EvmSnapshotPaths): String
    {
        val families = families(ref.env) ?: throw IllegalStateException("Polygon snapshot: unknown env '${ref.env}'")
        val borRoot = dir(ref.param("dir_bor")) ?: paths.root
        val heimdall = dir(ref.param("dir_heimdall"))
            ?: borRoot.parent?.resolve("heimdall")
            ?: throw IllegalStateException("Polygon snapshot: no Heimdall directory (disk layout has no 'heimdall' role)")
        val borData = borRoot.resolve("datadir").resolve("data")
        for (p in listOf(heimdall, borData))
        {
            // the script deletes data/ and bor/chaindata inside these: they must be real, deep paths
            if (p.nameCount < 2) throw IllegalStateException("Polygon snapshot: refusing to restore into $p")
        }
        val q = EvmSnapshotScripts::q
        return EvmSnapshotScripts.render(
            "polygon",
            paths,
            units = listOf(EvmUnitBodies.unitName("polygon", ref.env), PolygonUnitBodies.heimdallUnitName(ref.env)),
            vars = mapOf(
                "page_url" to q(PAGE_URL),
                "heimdall_prefix" to q(families.heimdall),
                "bor_prefixes" to q(families.bor.joinToString(" ")),
                "heimdall_home" to q(heimdall.toString()),
                "bor_data" to q(borData.toString()),
                "stage_dir" to q(paths.root.resolve("snapshots").toString()),
            ),
        )
    }

    private fun dir(raw: String): Path? =
        raw.trim().takeIf { it.startsWith("/") && !it.contains("..") && it.none { c -> c.isWhitespace() || c.isISOControl() } }
            ?.let { Path.of(it).toAbsolutePath().normalize() }
}
