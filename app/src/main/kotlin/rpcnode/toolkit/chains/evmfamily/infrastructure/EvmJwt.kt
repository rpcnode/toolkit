package rpcnode.toolkit.chains.evmfamily.infrastructure

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom

/** The Engine API secret shared by an execution client and its consensus client: `<node_dir>/.toolkit/jwt.hex`, 0600. */
object EvmJwt
{
    const val FILE = ".toolkit/jwt.hex"

    fun path(nodeDir: Path): Path = nodeDir.resolve(FILE)

    /** Creates the secret on the first Start and keeps it afterwards (both clients read the same file). */
    fun ensure(nodeDir: Path): Path
    {
        val file = path(nodeDir)
        if (Files.isRegularFile(file) && Files.readString(file).trim().length >= 64)
        {
            runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
            return file
        }
        Files.createDirectories(file.parent)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val tmp = file.resolveSibling("jwt.hex.tmp")
        Files.deleteIfExists(tmp)
        Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(tmp, bytes.joinToString("") { "%02x".format(it) } + "\n")
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return file
    }
}
