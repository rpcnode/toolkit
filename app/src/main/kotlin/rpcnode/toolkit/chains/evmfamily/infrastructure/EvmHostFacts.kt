package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.nio.file.Files
import java.nio.file.Path

/** Facts about the host that a recipe turns into limits (Go `GOMEMLIMIT`). */
object EvmHostFacts
{
    /** `MemTotal` of `/proc/meminfo` in whole GiB, or null when it cannot be read. */
    fun memTotalGiB(meminfo: Path = Path.of("/proc/meminfo")): Int?
    {
        val line = runCatching { Files.readAllLines(meminfo).firstOrNull { it.startsWith("MemTotal:") } }.getOrNull() ?: return null
        val kb = Regex("""\d+""").find(line)?.value?.toLongOrNull() ?: return null
        return (kb / 1_048_576L).toInt().takeIf { it > 0 }
    }

    /** Go soft memory limit (GiB) for a host with [totalGiB] of RAM: [percent] % of it, at least 1. */
    fun goMemLimitGiB(totalGiB: Int, percent: Int): Int = (totalGiB * percent / 100).coerceAtLeast(1)
}
