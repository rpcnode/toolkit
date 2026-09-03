package rpcnode.toolkit.shared.infrastructure.classpath

import java.io.File
import java.io.InputStream
import java.util.jar.JarFile

/**
 * Per-network shipped resources live under classpath `chains/<id>/`:
 * - `network.yml` — catalog + disk/CPU/RAM facts
 * - `clients.yml` — programs, ports, artifact URLs, snapshot mirrors
 * - `*.tmpl` — systemd / scripts (`node.service.tmpl`, …)
 *
 * `chains/default/` holds shared fallbacks (e.g. generic unit) and is not a network id.
 */
object ClasspathChainResources
{
    const val ROOT = "chains"
    const val NETWORK_YML = "network.yml"
    const val CLIENTS_YML = "clients.yml"
    const val DEFAULT_ID = "default"

    fun path(networkId: String, file: String): String =
        "$ROOT/${networkId.trim().lowercase().trim('/')}/${file.trim().trimStart('/')}"

    fun open(classLoader: ClassLoader, networkId: String, file: String): InputStream? =
        classLoader.getResourceAsStream(path(networkId, file))

    /**
     * Network ids that ship [leaf] under `chains/<id>/` (sorted).
     * Skips [DEFAULT_ID].
     */
    fun listIdsWith(classLoader: ClassLoader, leaf: String): List<String>
    {
        val wanted = leaf.trim().lowercase()
        if (wanted.isEmpty())
        {
            return emptyList()
        }
        val ids = linkedSetOf<String>()
        val urls = classLoader.getResources(ROOT)
        while (urls.hasMoreElements())
        {
            val url = urls.nextElement()
            when (url.protocol)
            {
                "file" ->
                {
                    File(url.toURI()).listFiles()?.forEach { dir ->
                        if (dir.isDirectory &&
                            !dir.name.equals(DEFAULT_ID, ignoreCase = true) &&
                            File(dir, wanted).isFile
                        )
                        {
                            ids += dir.name.lowercase()
                        }
                    }
                }
                "jar" ->
                {
                    val jarPath = url.path.substringBefore("!").removePrefix("file:")
                    val prefix = "$ROOT/"
                    JarFile(jarPath).use { jar ->
                        for (entry in jar.entries())
                        {
                            if (entry.isDirectory || !entry.name.startsWith(prefix))
                            {
                                continue
                            }
                            val rest = entry.name.removePrefix(prefix)
                            val slash = rest.indexOf('/')
                            if (slash <= 0)
                            {
                                continue
                            }
                            val id = rest.substring(0, slash).lowercase()
                            val file = rest.substring(slash + 1)
                            if (id == DEFAULT_ID || "/" in file)
                            {
                                continue
                            }
                            if (file.equals(wanted, ignoreCase = true))
                            {
                                ids += id
                            }
                        }
                    }
                }
            }
        }
        return ids.sorted()
    }
}
