package rpcnode.toolkit.agent.infrastructure.config

import rpcnode.toolkit.nodes.application.config.ClientConfigLeafPatch

/**
 * Host-side client config patch — delegates to [ClientConfigLeafPatch] (keep in sync).
 */
object HostClientConfigPatch
{
    fun apply(
        format: String,
        template: String,
        assignments: Map<String, String>,
        iniSection: String? = null,
        omitIniKeys: Set<String> = emptySet(),
    ): String
    {
        return when (format.trim().lowercase())
        {
            "flags" -> template
            "ini" -> ClientConfigLeafPatch.applyIni(template, assignments, iniSection, omitIniKeys)
            else -> ClientConfigLeafPatch.applyHocon(template, assignments)
        }
    }
}
