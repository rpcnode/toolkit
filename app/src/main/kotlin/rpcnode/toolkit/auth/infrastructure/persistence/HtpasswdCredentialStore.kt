package rpcnode.toolkit.auth.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.mindrot.jbcrypt.BCrypt
import rpcnode.toolkit.auth.domain.model.Username
import rpcnode.toolkit.auth.domain.repository.CredentialStore

class HtpasswdCredentialStore(
    private val path: Path,
    private val bcryptRounds: Int = 10,
) : CredentialStore
{
    private val lock = Any()

    override suspend fun hasUsers(): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) { loadLocked().isNotEmpty() }
    }

    override suspend fun create(username: Username, password: String) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val users = loadLocked().toMutableMap()
            users[username.value] = toHtpasswd(BCrypt.hashpw(password, BCrypt.gensalt(bcryptRounds)))
            writeLocked(users)
        }
    }

    override suspend fun verify(username: Username, password: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val hash = loadLocked()[username.value] ?: return@synchronized false
            BCrypt.checkpw(password, toJava(hash))
        }
    }

    private fun loadLocked(): Map<String, String>
    {
        if (!Files.isRegularFile(path))
        {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        Files.readAllLines(path).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#"))
            {
                return@forEach
            }
            val i = line.indexOf(':')
            if (i <= 0)
            {
                return@forEach
            }
            out[line.substring(0, i)] = line.substring(i + 1)
        }
        return out
    }

    private fun writeLocked(users: Map<String, String>)
    {
        val parent = path.parent
        if (parent != null)
        {
            Files.createDirectories(parent)
        }
        val body = users.entries.sortedBy { it.key }.joinToString("") { "${it.key}:${it.value}\n" }
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        Files.writeString(tmp, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        try
        {
            Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        catch (_: Exception)
        {
            Files.writeString(path, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
            Files.deleteIfExists(tmp)
        }
    }

    private fun toHtpasswd(hash: String): String =
        if (hash.startsWith("\$2a\$")) "\$2y\$" + hash.substring(4) else hash

    private fun toJava(hash: String): String =
        if (hash.startsWith("\$2y\$")) "\$2a\$" + hash.substring(4) else hash
}
