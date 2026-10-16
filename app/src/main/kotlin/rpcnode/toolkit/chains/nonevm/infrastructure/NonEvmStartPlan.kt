package rpcnode.toolkit.chains.nonevm.infrastructure

import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import rpcnode.toolkit.nodes.application.disks.decodeNodeDiskLayout
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan
import rpcnode.toolkit.nodes.application.start.NodeHeightSpec
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec

/** What the panel-side `ChainNodeStart` of every network in the family needs: wizard options, disk roles, launch args. */
object NonEvmStartPlan
{
    private val json = Json { ignoreUnknownKeys = true }

    /** One string option of the persisted wizard document (`install_options`), or null when absent / blank. */
    fun option(installOptionsJson: String, key: String): String? =
        runCatching { json.parseToJsonElement(installOptionsJson.ifBlank { "{}" }) as? JsonObject }
            .getOrNull()
            ?.get(key)
            ?.let { it as? JsonPrimitive }
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Directory of a Disks role, falling back to the node dir (a single-disk host has no layout). */
    fun roleDir(ctx: ChainNodeStartContext, roleId: String): String
    {
        val layout = decodeNodeDiskLayout(ctx.diskLayoutJson)
        return layout?.roles?.firstOrNull { it.id == roleId }?.dir?.trim().orEmpty()
            .ifEmpty { ctx.nodeDir?.trim().orEmpty() }
    }

    fun plan(
        entry: String,
        logFile: String,
        heightKind: String,
        args: List<String>,
        heightRole: String = "http",
    ): ChainNodeStartPlan =
        ChainNodeStartPlan(
            launch = NodeLaunchSpec(
                kind = "binary",
                entry = entry,
                args = args,
                extractArchiveGlob = null,
                normalizeDir = null,
                logFile = logFile,
            ),
            height = NodeHeightSpec(kind = heightKind, portRole = heightRole),
        )
}

/** `--toolkit-<name>=<value>` launch args: the panel's Start plan → the agent's process starter. */
object ToolkitArgs
{
    fun value(args: List<String>, name: String): String? =
        args.firstOrNull { it.startsWith("--toolkit-$name=") }
            ?.removePrefix("--toolkit-$name=")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * [launch] without the args that carry a secret (an Ethereum RPC URL with an API key, a Bitcoin RPC password):
     * `installCustomUnits` records the args in `.toolkit/launch.json`, which is world-readable. Secrets go to 0600 files.
     */
    fun withoutSecrets(launch: NodeLaunchSpec, vararg names: String): NodeLaunchSpec =
        launch.copy(args = launch.args.filterNot { a -> names.any { a.startsWith("--toolkit-$it=") } })

    /** An absolute path without `..`, or null. */
    fun path(args: List<String>, name: String): Path?
    {
        val raw = value(args, name) ?: return null
        if (!raw.startsWith("/") || raw.contains(".."))
        {
            return null
        }
        return Path.of(raw).toAbsolutePath().normalize()
    }
}
