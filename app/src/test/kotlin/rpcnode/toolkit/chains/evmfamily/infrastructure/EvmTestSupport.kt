package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.nio.file.Files
import java.nio.file.Path

/** Builds a start context without touching a host: binaries / JWT / scratch values are stand-ins. */
object EvmTestSupport
{
    fun context(
        profile: EvmProfile,
        envId: String = profile.envs.first().id,
        options: EvmLaunchOptions? = null,
        mem: Int? = 64,
        root: Path = Files.createTempDirectory("evm-test"),
    ): EvmStartContext
    {
        val env = profile.env(envId)
        val opts = options ?: EvmLaunchOptions(env = env.id)
        val bins = profile.recipe.binaries(env).associate { it.name to root.resolve("bin").resolve(it.name) }
        val dirs = profile.diskRoles.associateWith { root.resolve(it) }
        return EvmStartContext(
            profile = profile,
            env = env,
            nodeId = "node-1",
            root = root,
            bins = bins,
            dirs = dirs,
            options = opts,
            jwt = if (profile.recipe.usesJwt) root.resolve(".toolkit/jwt.hex") else null,
            memTotalGiB = mem,
        ).also {
            it.scratch["bootstrap.ip"] = "34.79.60.151:9651"
            it.scratch["bootstrap.id"] = "NodeID-NCfvDEdDv8tuVDxWLB1ShJToQ7qAcca2B"
            it.scratch["bootnodes"] = "enode://aa@1.2.3.4:30303,enode://bb@5.6.7.8:30303"
        }
    }

    /** `--flag` → values of every occurrence of one unit's ExecStart (`--k v`, `--k=v` and bare `--k` all handled). */
    fun flags(unitBody: String): Map<String, List<String>>
    {
        val lines = unitBody.lines()
        val start = lines.indexOfFirst { it.startsWith("ExecStart=") }
        require(start >= 0) { "no ExecStart in\n$unitBody" }
        var end = start
        while (lines[end].trimEnd().endsWith("\\")) end++
        val out = linkedMapOf<String, MutableList<String>>()
        for (raw in lines.subList(start + 1, end + 1))
        {
            val t = raw.trim().removeSuffix("\\").trim()
            if (!t.startsWith("--")) continue
            val eq = t.indexOf('=')
            val sp = t.indexOf(' ')
            val cut = listOf(eq, sp).filter { it >= 0 }.minOrNull() ?: t.length
            out.getOrPut(t.substring(0, cut)) { mutableListOf() } += t.substring(minOf(cut + 1, t.length)).trim()
        }
        return out
    }

    fun command(unitBody: String): String =
        unitBody.lines().first { it.startsWith("ExecStart=") }.removePrefix("ExecStart=").removeSuffix("\\").trim()

    fun unitSetting(unitBody: String, key: String): String? =
        unitBody.lines().firstOrNull { it.startsWith("$key=") }?.substringAfter('=')

    /** Every service of [units] that runs a command; timers (Monad's clean-up) have no ExecStart and are checked by their network's test. */
    fun allUnits(units: EvmUnits): List<String> =
        (listOf(units.primary) + units.companions.map { it.second }).filter { it.lines().any { l -> l.startsWith("ExecStart=") } }
}
