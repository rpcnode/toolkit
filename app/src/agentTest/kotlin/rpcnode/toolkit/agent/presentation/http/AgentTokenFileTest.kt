package rpcnode.toolkit.agent.presentation.http

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.agent.infrastructure.filesystem.ProjectAgentDirectories
import rpcnode.toolkit.agent.infrastructure.filesystem.defaultAgentLogFile
import rpcnode.toolkit.agent.infrastructure.log.installAgentFileLog
import rpcnode.toolkit.agent.infrastructure.log.logbackPath

class AgentTokenFileTest
{
    @Test
    fun rpcnode_dev_env_values()
    {
        assertTrue(rpcnodeDev("1"))
        assertTrue(rpcnodeDev("true"))
        assertTrue(rpcnodeDev("YES"))
        assertFalse(rpcnodeDev(null))
        assertFalse(rpcnodeDev(""))
        assertFalse(rpcnodeDev("0"))
    }

    @Test
    fun token_from_file_is_the_trimmed_contents()
    {
        val file = Files.createTempFile("agent", ".token")
        Files.writeString(file, "  secret-from-file  \n")
        val cfg = AgentConfig(tokenFile = file)
        assertEquals("secret-from-file", cfg.token)
    }

    @Test
    fun missing_file_is_empty()
    {
        val file = Files.createTempDirectory("agent-token").resolve("nope")
        val cfg = AgentConfig(tokenFile = file)
        assertEquals("", cfg.token)
    }

    @Test
    fun env_overrides_the_config_dir()
    {
        val got = AgentConfig.defaultTokenFile(
            tokenFileEnv = "/tmp/explicit.token",
            configDir = Path.of("/unused"),
        )
        assertEquals(Path.of("/tmp/explicit.token"), got)
    }

    @Test
    fun default_is_agent_token_under_the_config_dir()
    {
        val dir = Path.of("/home/nik/.config/rpcnode-agent")
        val got = AgentConfig.defaultTokenFile(tokenFileEnv = null, configDir = dir)
        assertEquals(dir.resolve("agent.token"), got)
    }

    @Test
    fun project_directories_resolves_config_cache_and_log_on_this_os()
    {
        val dirs = ProjectAgentDirectories()
        assertTrue(dirs.configDir().toString().isNotBlank())
        assertTrue(dirs.cacheDir().toString().isNotBlank())
        assertTrue(dirs.logDir().toString().isNotBlank())
        assertTrue("rpcnode" in dirs.configDir().toString().lowercase())
        assertTrue("rpcnode" in dirs.cacheDir().toString().lowercase())
        assertTrue("rpcnode" in dirs.logDir().toString().lowercase())
    }

    @Test
    fun env_overrides_keep_dirs_next_to_the_project()
    {
        val root = Files.createTempDirectory("agent-dev")
        val dirs = ProjectAgentDirectories(
            configDirEnv = root.resolve("config").toString(),
            cacheDirEnv = root.resolve("cache").toString(),
            logDirEnv = root.resolve("logs").toString(),
        )
        assertEquals(root.resolve("config"), dirs.configDir())
        assertEquals(root.resolve("cache"), dirs.cacheDir())
        assertEquals(root.resolve("logs"), dirs.logDir())
    }

    @Test
    fun log_file_env_overrides_the_log_dir()
    {
        val got = defaultAgentLogFile(
            logFileEnv = "/tmp/explicit.log",
            logDir = Path.of("/unused"),
        )
        assertEquals(Path.of("/tmp/explicit.log"), got)
    }

    @Test
    fun default_log_is_agent_log_under_the_log_dir()
    {
        val dir = Path.of("/home/nik/.cache/rpcnode-agent/logs")
        val got = defaultAgentLogFile(logFileEnv = null, logDir = dir)
        assertEquals(dir.resolve("rpcnode-agent.log"), got)
    }

    @Test
    fun logback_path_has_no_backslashes()
    {
        val file = Files.createTempDirectory("agent-log").resolve("rpcnode-agent.log")
        val path = logbackPath(file)
        assertTrue('\\' !in path)
        assertTrue(path.endsWith("rpcnode-agent.log"))
    }

    @Test
    fun file_log_writes_on_this_os()
    {
        val file = Files.createTempDirectory("agent-log").resolve("rpcnode-agent.log")
        installAgentFileLog(file)
        val log = org.slf4j.LoggerFactory.getLogger("rpcnode-agent-write-test")
        log.info("write-check")
        val ctx = org.slf4j.LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
        val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val appenders = root.iteratorForAppenders()
        while (appenders.hasNext())
        {
            val appender = appenders.next()
            if (appender is ch.qos.logback.core.rolling.RollingFileAppender<*> &&
                appender.file.replace('\\', '/') == logbackPath(file)
            )
            {
                appender.stop()
                root.detachAppender(appender)
            }
        }
        assertTrue(Files.isRegularFile(file), "missing $file")
        assertTrue(Files.readString(file).contains("write-check"))
    }
}
