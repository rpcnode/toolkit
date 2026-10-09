package rpcnode.toolkit.agent.infrastructure.proc

/** True when the agent process runs as uid 0 (needed for systemd node units). */
fun runningAsRoot(): Boolean
{
    return try
    {
        val p = ProcessBuilder("id", "-u").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor() == 0 && out == "0"
    }
    catch (_: Exception)
    {
        false
    }
}
