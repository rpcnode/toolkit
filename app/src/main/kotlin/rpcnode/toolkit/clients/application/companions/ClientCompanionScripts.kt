package rpcnode.toolkit.clients.application.companions

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import rpcnode.toolkit.catalog.domain.NetworkId

/**
 * Extra files shipped beside downloaded client artifacts into
 * `CLIENT_SYNC_DEST/<network>/<env>/` (and then to the host via agent sync).
 * Source of truth: classpath `chains/<id>/scripts/`.
 */
fun interface ClientCompanionScripts
{
    /** Copy companions for [network] into [destDir]. Returns basenames written. */
    fun ship(network: NetworkId, destDir: Path): List<String>
}

/**
 * Maps network to classpath resources under `chains/<id>/scripts/` copied as flat files
 * into the panel clients dir (not under `public/`).
 */
class ClasspathClientCompanionScripts(
    private val classLoader: ClassLoader = ClasspathClientCompanionScripts::class.java.classLoader,
) : ClientCompanionScripts
{
    override fun ship(network: NetworkId, destDir: Path): List<String>
    {
        val names = SCRIPTS[network] ?: return emptyList()
        if (names.isEmpty())
        {
            return emptyList()
        }
        Files.createDirectories(destDir)
        val written = mutableListOf<String>()
        for (name in names)
        {
            val resource = "chains/${network.value}/scripts/$name"
            val stream = classLoader.getResourceAsStream(resource) ?: continue
            val dest = destDir.resolve(name)
            stream.use { input ->
                Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
            }
            written += name
        }
        return written
    }

    companion object
    {
        val SCRIPTS: Map<NetworkId, List<String>> = mapOf(
            NetworkId.SOLANA to listOf(
                "run-validator.sh.tmpl",
                "build-agave.sh.tmpl",
            ),
        )
    }
}
