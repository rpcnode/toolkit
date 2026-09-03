package rpcnode.toolkit.clients.domain.model

/** Whether a catalog port is written into the client config on Start. */
enum class PortConfigPolicy
{
    /** Always patched into the config (P2P, RPC, …). */
    REQUIRED,

    /** Operator toggles on Start (`install_options.port_<role>` = 1/0). Default off. */
    OPTIONAL,

    /** Check-ports only — never written into the client config. */
    NONE,
    ;

    companion object
    {
        fun parse(raw: String?): PortConfigPolicy
        {
            return when (raw?.trim()?.lowercase())
            {
                "optional" -> OPTIONAL
                "none", "off", "false", "check_only", "check-only" -> NONE
                else -> REQUIRED
            }
        }
    }
}

fun portConfigInstallOptionKey(role: String): String =
    "port_${role.trim().lowercase()}"

fun catalogPortConfigEnabled(
    role: String,
    ports: List<ProgramPort>,
    options: Map<String, String>,
): Boolean
{
    val spec = ports.firstOrNull { it.role.equals(role, ignoreCase = true) } ?: return true
    return when (spec.configPolicy)
    {
        PortConfigPolicy.REQUIRED -> true
        PortConfigPolicy.OPTIONAL ->
            options[portConfigInstallOptionKey(spec.role)]?.trim() == "1"
        PortConfigPolicy.NONE -> false
    }
}

fun isCatalogPortBindingSource(source: String): Boolean
{
    return source.trim().lowercase() in setOf("catalog_port", "catalog_zmq_bind")
}
