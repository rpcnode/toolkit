package rpcnode.toolkit.panel.infrastructure.filesystem

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.panel.infrastructure.log.logbackPath

class ProjectServerDirectoriesTest
{
    @Test
    fun project_directories_resolves_config_cache_and_log_on_this_os()
    {
        val dirs = ProjectServerDirectories()
        assertTrue(dirs.configDir().toString().isNotBlank())
        assertTrue(dirs.cacheDir().toString().isNotBlank())
        assertTrue(dirs.logDir().toString().isNotBlank())
        assertTrue("rpcnode" in dirs.configDir().toString().lowercase())
        assertTrue("rpcnode" in dirs.cacheDir().toString().lowercase())
        assertTrue("rpcnode" in dirs.logDir().toString().lowercase())
    }

    @Test
    fun default_log_is_server_log_under_the_log_dir()
    {
        val dir = Path.of("/home/nik/.cache/rpcnode-server/logs")
        val got = defaultServerLogFile(logFileEnv = null, logDir = dir)
        assertEquals(dir.resolve("server.log"), got)
    }

    @Test
    fun log_file_env_overrides_the_log_dir()
    {
        val got = defaultServerLogFile(
            logFileEnv = "/tmp/explicit.log",
            logDir = Path.of("/unused"),
        )
        assertEquals(Path.of("/tmp/explicit.log"), got)
    }

    @Test
    fun logback_path_has_no_backslashes()
    {
        val file = Path.of("server.log")
        val path = logbackPath(file)
        assertTrue('\\' !in path)
        assertTrue(path.endsWith("server.log"))
    }
}
