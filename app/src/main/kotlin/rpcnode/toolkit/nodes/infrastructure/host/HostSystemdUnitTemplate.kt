package rpcnode.toolkit.nodes.infrastructure.host

/**
 * Loads `chains/<network>/<file>` from the classpath, falling back to
 * `chains/default/node.service.tmpl` when [file] is the primary unit template.
 * Placeholders use `{{name}}` substitution.
 */
object HostSystemdUnitTemplate
{
    fun load(network: String, file: String = "node.service.tmpl"): String
    {
        val id = network.trim().lowercase()
        val name = file.trim().ifEmpty { "node.service.tmpl" }
        val preferred = "chains/$id/$name"
        val fallback = "chains/default/node.service.tmpl"
        val cl = HostSystemdUnitTemplate::class.java.classLoader
        val preferredStream = cl.getResourceAsStream(preferred)
        if (preferredStream != null)
        {
            return preferredStream.bufferedReader().use { it.readText() }.trimEnd() + "\n"
        }
        if (name == "node.service.tmpl")
        {
            val raw = cl.getResourceAsStream(fallback)?.bufferedReader()?.use { it.readText() }
                ?: error("missing systemd template $preferred and $fallback")
            return raw.trimEnd() + "\n"
        }
        error("missing systemd template $preferred")
    }

    fun render(template: String, vars: Map<String, String>): String
    {
        var out = template
        for ((key, value) in vars)
        {
            out = out.replace("{{$key}}", value)
        }
        return out
    }
}
