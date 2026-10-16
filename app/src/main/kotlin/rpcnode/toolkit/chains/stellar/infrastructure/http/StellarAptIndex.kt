package rpcnode.toolkit.chains.stellar.infrastructure.http

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AptPackage(
    val name: String,
    val version: String,
    val filename: String,
    val size: Long,
    val sha256: String,
)

/** Newest stable package in the SDF apt repository (`apt.stellar.org`), read from `Packages.gz` and cached 10 minutes. */
open class StellarAptIndex(
    private val fetch: (suite: String) -> InputStream? = ::fetchPackagesGz,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 10 * 60 * 1000,
)
{
    private val cache = mutableMapOf<String, Pair<Long, List<AptPackage>>>()

    open suspend fun newest(pkg: String, suite: String = "noble"): AptPackage?
    {
        val packages = packages(suite) ?: return null
        return packages
            .filter { it.name == pkg && '~' !in it.version }
            .filter { pkg != "stellar-core" || it.filename.endsWith(".${suite}_amd64.deb") }
            .maxWithOrNull { a, b -> compareKeys(versionKey(a.version), versionKey(b.version)) }
    }

    private suspend fun packages(suite: String): List<AptPackage>?
    {
        val now = nowMillis()
        synchronized(cache) { cache[suite]?.takeIf { now - it.first < ttlMillis } }?.let { return it.second }
        val parsed = withContext(Dispatchers.IO) {
            runCatching { fetch(suite)?.use { String(it.readBytes(), Charsets.UTF_8) }?.let(::parse) }.getOrNull()
        }
        if (parsed == null)
        {
            return synchronized(cache) { cache[suite]?.second }
        }
        synchronized(cache) { cache[suite] = now to parsed }
        return parsed
    }

    companion object
    {
        fun parse(packagesText: String): List<AptPackage> =
            packagesText.split(Regex("\\n\\s*\\n")).mapNotNull { stanza ->
                val fields = stanza.lines().mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0 || line.first().isWhitespace()) null else line.substring(0, i) to line.substring(i + 1).trim()
                }.toMap()
                val name = fields["Package"] ?: return@mapNotNull null
                AptPackage(
                    name = name,
                    version = fields["Version"] ?: return@mapNotNull null,
                    filename = fields["Filename"] ?: return@mapNotNull null,
                    size = fields["Size"]?.toLongOrNull() ?: 0,
                    sha256 = fields["SHA256"].orEmpty(),
                )
            }

        /** Numeric parts of the version before `~`: `29.0.1-213` -> [29,0,1,213], `29.0.0-3589.4eb83.noble` -> [29,0,0,3589]. */
        fun versionKey(version: String): List<Long>
        {
            val numeric = Regex("^\\d+$")
            return version.substringBefore('~').split('.', '-').map { part -> if (numeric.matches(part)) part.toLongOrNull() else null }
                .takeWhile { it != null }.filterNotNull()
        }

        private fun compareKeys(a: List<Long>, b: List<Long>): Int
        {
            for (i in 0 until maxOf(a.size, b.size))
            {
                val c = a.getOrElse(i) { 0 }.compareTo(b.getOrElse(i) { 0 })
                if (c != 0) return c
            }
            return 0
        }

        fun fetchPackagesGz(suite: String): InputStream?
        {
            val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            val req = HttpRequest.newBuilder(URI("https://apt.stellar.org/dists/$suite/stable/binary-amd64/Packages.gz"))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "rpcnode-server")
                .GET()
                .build()
            val resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream())
            if (resp.statusCode() !in 200..299)
            {
                resp.body().close()
                return null
            }
            return GZIPInputStream(resp.body())
        }
    }
}
