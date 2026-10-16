package rpcnode.toolkit.clients.application

import java.nio.file.Path
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec

/**
 * One compile of [spec] at [version]/[tag]; the built files are written into [outDir]. [platforms] narrows the build to
 * the architectures the fleet actually runs (null = everything the program's `build` block lists).
 */
data class ClientBuildRequest(
    val spec: ClientProgramSpec,
    val version: String,
    val tag: String,
    val outDir: Path,
    val platforms: List<String>? = null,
)

sealed interface ClientBuildResult
{
    /** [names] are the files now in [ClientBuildRequest.outDir], each already tagged with its arch. */
    data class Built(val names: List<String>) : ClientBuildResult

    /** No way to build here (no Docker): the caller publishes the source only and hosts compile it themselves. */
    data class Unavailable(val reason: String) : ClientBuildResult

    data class Failed(val error: String) : ClientBuildResult
}

/** Compiles a client from source so hosts download a ready binary instead of building it themselves. */
fun interface ClientBuilder
{
    suspend fun build(request: ClientBuildRequest, onLog: (String) -> Unit): ClientBuildResult
}

/** File a build publishes for [output] on [arch]: `xrpld` + `x86_64` → `xrpld-x86_64`. */
fun builtArtifactName(output: String, arch: String): String = "$output-$arch"
