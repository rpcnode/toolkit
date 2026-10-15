package rpcnode.toolkit.chains.xrpl.infrastructure

import java.nio.file.Files
import java.nio.file.Path

/**
 * `xrpld` comes either prebuilt (compiled by the panel's build hosts, `xrpld-<arch>`) or is compiled on the host from the
 * XRPLF/rippled source release by the run script (see `run.sh.tmpl`); this only locates those files and the binary left
 * under `{nodeDir}/bin/`.
 */
object XrplHostBinaries
{
    const val BIN = XrplUnitBodies.BIN_NAME
    const val SOURCE_ARCHIVE = "xrpld-src.tar.gz"

    fun sourceArchive(nodeDir: Path): Path? =
        nodeDir.resolve(SOURCE_ARCHIVE).takeIf { Files.isRegularFile(it) && Files.size(it) > 0 }

    /** `xrpld-<arch>` compiled on a build host of this CPU architecture and synced with the client; null when absent. */
    fun prebuilt(nodeDir: Path, arch: String = hostArch()): Path? =
        nodeDir.resolve("$BIN-$arch").takeIf { Files.isRegularFile(it) && Files.size(it) > 0 && isElf(it) }

    fun hostArch(): String =
        if (System.getProperty("os.arch").orEmpty().lowercase().let { it.contains("aarch64") || it.contains("arm64") }) "aarch64" else "x86_64"

    fun binary(nodeDir: Path): Path = nodeDir.resolve("bin").resolve(BIN)

    internal fun isElf(path: Path): Boolean =
        runCatching {
            Files.newInputStream(path).use { input ->
                val head = ByteArray(4)
                input.read(head) == 4 && head.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            }
        }.getOrDefault(false)
}
