package rpcnode.toolkit.clients.domain.model

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId

/** How to find out the latest version of a [ClientProgramSpec]. */
sealed interface ClientVersionSource
{
    /**
     * Live-probed via the GitHub Releases API. [tagPrefix], when set, filters candidate tags.
     * [includePrerelease] follows release candidates too (for a network that needs one, e.g. a testnet
     * fork shipped before the stable release); the newest release wins, so stable takes over by itself.
     */
    data class GitHubRelease(
        val repo: String,
        val tagPrefix: String? = null,
        val includePrerelease: Boolean = false,
    ) : ClientVersionSource

    /**
     * No live probing — a manually maintained known-good build (e.g. tron, which has no public
     * releases feed to probe): "latest" is whatever this install was shipped with, bumped by hand
     * in the catalog YAML when a new build is verified.
     */
    data class Pinned(val version: String, val tag: String, val label: String) : ClientVersionSource
}

enum class ClientArtifactRole { ARTIFACT, CONFIG }

/**
 * One downloadable file. [urlTemplate] may contain `{version}`/`{tag}` placeholders, substituted
 * with the resolved [ClientVersionSource] result at download time.
 */
data class ClientArtifactSpec(
    val name: String,
    val role: ClientArtifactRole,
    val urlTemplate: String,
    val urlTemplateAarch64: String? = null,
    /** Stable on-disk name when downloading the aarch64 URL (defaults to [name]). */
    val nameAarch64: String? = null,
    val optional: Boolean = false,
)

/**
 * A TCP port the program listens on, fixed by the client software itself (its own default,
 * not chosen by us) — e.g. Bitcoin Core's P2P port, java-tron's HTTP API port.
 */
data class ProgramPort(
    val role: String,
    val port: Int,
    val label: String = "",
    val configPolicy: PortConfigPolicy = PortConfigPolicy.REQUIRED,
    /** What the client binds on this port: decides which host bind test says «in use». */
    val protocol: PortProtocol = PortProtocol.TCP,
)

/** `protocol` in chains/<id>/clients.yml → ports[]; default `tcp`. TON's validator is UDP-only. */
enum class PortProtocol(val id: String)
{
    TCP("tcp"),
    UDP("udp"),
    BOTH("both"),
    ;

    companion object
    {
        fun parse(raw: String?): PortProtocol =
            when (raw?.trim()?.lowercase())
            {
                "udp" -> UDP
                "both", "tcp+udp", "tcp_udp" -> BOTH
                else -> TCP
            }
    }
}

/** Host runtime needs declared in `clients/<network>.yml` → `programs[].requirements`. */
data class ClientProgramRequirements(
    /** JDK major for `java_jar` launch (java-tron needs 8 on amd64). */
    val javaMajor: Int? = null,
    /**
     * Process log relative to node_dir (e.g. `logs/tron.log`).
     * Null → host default `logs/node.out` (systemd stdout).
     */
    val logFile: String? = null,
)

/** What one program on one network/env can download — shipped catalog data, not runtime state. */
data class ClientProgramSpec(
    val network: NetworkId,
    val env: EnvId,
    val programId: String,
    val source: ClientVersionSource,
    val artifacts: List<ClientArtifactSpec> = emptyList(),
    val configs: List<ClientArtifactSpec> = emptyList(),
    val ports: List<ProgramPort> = emptyList(),
    val requirements: ClientProgramRequirements = ClientProgramRequirements(),
    val skipReason: String? = null,
)
