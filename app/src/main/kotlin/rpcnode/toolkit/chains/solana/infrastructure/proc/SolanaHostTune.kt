package rpcnode.toolkit.chains.solana.infrastructure.proc

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import rpcnode.toolkit.chains.solana.infrastructure.SolanaSysctlTuning

/**
 * Host sysctl for Agave (Go `ensureSysctl` / Anza system-tuning docs).
 * Without adequate UDP buffers, `agave-validator` exits with "OS network limit test failed".
 */
object SolanaHostTune
{
    const val CONF_PATH = "/etc/sysctl.d/21-solana.conf"

    /** @deprecated Prefer [SolanaSysctlTuning.confBody] — kept for tests. */
    val CONF_BODY: String = SolanaSysctlTuning.recommended.confBody()

    sealed interface Result
    {
        data object Ok : Result
        data class Failed(val detail: String) : Result
    }

    /** Live `/proc/sys/…` values for the Agave knobs (null when unreadable). */
    fun readCurrent(): Map<String, Long?>
    {
        return SolanaSysctlTuning.KEYS.associateWith { key ->
            val rel = key.replace('.', '/')
            val file = Path.of("/proc/sys", rel)
            runCatching {
                Files.readString(file).trim().substringBefore('\n').toLongOrNull()
            }.getOrNull()
        }
    }

    fun ensureSysctl(tuning: SolanaSysctlTuning = SolanaSysctlTuning.recommended): Result
    {
        val body = tuning.confBody()
        val path = Path.of(CONF_PATH)
        return try
        {
            val existing = if (Files.isRegularFile(path))
            {
                Files.readString(path)
            }
            else
            {
                ""
            }
            if (existing != body)
            {
                Files.createDirectories(path.parent)
                Files.writeString(
                    path,
                    body,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE,
                )
            }
            val pb = ProcessBuilder("sysctl", "--system")
            pb.redirectErrorStream(true)
            val p = pb.start()
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            if (code != 0)
            {
                val pb2 = ProcessBuilder("sysctl", "-p", CONF_PATH)
                pb2.redirectErrorStream(true)
                val p2 = pb2.start()
                val out2 = p2.inputStream.bufferedReader().readText()
                val code2 = p2.waitFor()
                if (code2 != 0)
                {
                    return Result.Failed(
                        "sysctl apply failed (exit $code / $code2). " +
                            "Need root. Tail: ${(out + "\n" + out2).takeLast(400)}",
                    )
                }
            }
            Result.Ok
        }
        catch (e: Exception)
        {
            Result.Failed(e.message ?: "sysctl tune failed")
        }
    }
}
