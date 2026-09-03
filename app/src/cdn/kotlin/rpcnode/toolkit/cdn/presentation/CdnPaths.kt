package rpcnode.toolkit.cdn.presentation

import java.nio.file.Files
import java.nio.file.Path

object CdnPaths
{
    /** Directory that contains the fat JAR, or cwd when running from classes. */
    fun installRoot(): Path
    {
        val loc = CdnConfig::class.java.protectionDomain.codeSource?.location ?: return cwd()
        return try
        {
            val path = Path.of(loc.toURI()).toAbsolutePath().normalize()
            if (Files.isRegularFile(path) && path.fileName.toString().endsWith(".jar"))
            {
                path.parent ?: cwd()
            }
            else
            {
                cwd()
            }
        }
        catch (_: Exception)
        {
            cwd()
        }
    }

    private fun cwd(): Path = Path.of(System.getProperty("user.dir") ?: ".").toAbsolutePath().normalize()
}
