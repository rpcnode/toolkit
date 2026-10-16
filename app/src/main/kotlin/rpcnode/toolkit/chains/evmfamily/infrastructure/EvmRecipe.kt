package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.nio.file.Path

/** How one file the Clients step synced into node_dir becomes an executable under `<node_dir>/bin/`. */
data class EvmBinary(
    /** File name under `bin/` (for [Kind.ZIP_BUNDLE]: the entry point that must exist after the unpack). */
    val name: String,
    /** Glob of the synced file in node_dir (versionless on-disk names, see clients.yml). */
    val synced: String,
    val kind: Kind,
    /** Glob of the executable's file name inside an archive (default: [name]); Tempo ships `tempo-<tag>-<target>`. */
    val member: String = name,
)
{
    enum class Kind
    {
        /** The synced file is the executable. */
        RAW,

        /** `.tar.gz`: the first file matching [member] anywhere inside is the executable. */
        TAR_MEMBER,

        /** `.zip`: the first file matching [member] anywhere inside is the executable. */
        ZIP_MEMBER,

        /** `.zip`: the whole app (dlls, configs/, chainspec/) is unpacked into `bin/`. */
        ZIP_BUNDLE,

        /**
         * `.deb`: [EvmBinary.member] is the file under `usr/local/bin/` of the package. All binaries of one synced deb are
         * taken in a single pass, and every file in the package's `usr/local/lib` goes to `<node_dir>/lib/` (the client finds its own
         * `.so` files through `LD_LIBRARY_PATH`, no root and no `dpkg -i`).
         */
        DEB_BUNDLE,
    }
}

/** The units one Start installs: the primary unit and its companions (consensus clients). */
data class EvmUnits(
    val primary: String,
    val companions: List<Pair<String, String>> = emptyList(),
    val startCompanionsFirst: Boolean = false,
)

/**
 * The part of an EVM-family network that is not shared: which binaries it needs, what runs once before the units are
 * written, and the units themselves. [units] is a pure function of the context (tested without a host); [prepare] is
 * the only place that touches the host (init commands, config patches).
 */
interface EvmRecipe
{
    fun binaries(env: EvmEnv): List<EvmBinary>

    /** True when the units share an Engine API JWT at `<node_dir>/.toolkit/jwt.hex`. */
    val usesJwt: Boolean get() = false

    /** Host preparation before the units are written; null = done, otherwise the failure shown to the operator. */
    fun prepare(ctx: EvmStartContext): String? = null

    fun units(ctx: EvmStartContext): EvmUnits

    /** Scripted snapshot (`evm-snapshot://` sentinel): the bash script for [ref], or null when this network has none. */
    fun snapshotScript(ref: EvmSnapshotRef, paths: EvmSnapshotPaths): String? = null
}

/** Everything a recipe may read while rendering units. */
class EvmStartContext(
    val profile: EvmProfile,
    val env: EvmEnv,
    val nodeId: String,
    val root: Path,
    val bins: Map<String, Path>,
    /** Disk role id → directory (from the wizard's disk layout; node_dir when the layout has no such role). */
    val dirs: Map<String, Path>,
    val options: EvmLaunchOptions,
    val jwt: Path?,
    /** Host RAM in GiB when known (Go memory limits). */
    val memTotalGiB: Int? = null,
)
{
    /** What [EvmRecipe.prepare] hands to [EvmRecipe.units] (looked-up bootstrap peers, a chosen file). */
    val scratch: MutableMap<String, String> = mutableMapOf()

    fun port(role: String): Int = profile.port(env, role)

    fun bin(name: String): Path = bins[name] ?: error("binary '$name' not installed")

    fun dir(role: String): Path = dirs[role] ?: root

    val logsDir: Path get() = root.resolve("logs")

    val toolkitDir: Path get() = root.resolve(".toolkit")

    val primaryUnit: String get() = EvmUnitBodies.unitName(profile.network.value, env.id)

    fun companionUnit(role: String): String = EvmUnitBodies.companionName(profile.network.value, role, env.id)
}
