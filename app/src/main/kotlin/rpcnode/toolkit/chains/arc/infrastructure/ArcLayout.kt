package rpcnode.toolkit.chains.arc.infrastructure

import java.nio.file.Path

/**
 * Where the Arc data lives under the execution disk role. The role dir itself also holds the binaries, logs and `.toolkit`,
 * and `arc-snapshots` refuses a directory that has data it did not restore — so the two databases get their own subdirectories.
 */
object ArcLayout
{
    fun execution(root: Path): Path = root.resolve("execution")

    fun consensus(root: Path): Path = root.resolve("consensus")

    /** Marker of a finished snapshot restore; written by the wizard's Snapshot step and by the run script. */
    fun snapshotMarker(root: Path, kind: String): Path =
        root.resolve(".toolkit").resolve("arc-snapshot-${ArcNodeMode.normalize(kind)}.done")
}
