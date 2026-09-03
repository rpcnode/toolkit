package rpcnode.toolkit.agent.presentation.http

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentSystemInstallTest
{
    @Test
    fun unit_and_env_bodies()
    {
        val unit = AgentSystemInstall.unitFileBody(
            javaBin = "/opt/rpcnode/jdk/bin/java",
            jarFile = java.nio.file.Path.of("/opt/rpcnode/lib/rpcnode-agent.jar"),
            envFile = java.nio.file.Path.of("/etc/rpcnode/rpcnode-agent.env"),
            workingDir = java.nio.file.Path.of("/opt/rpcnode"),
        )
        assertTrue(
            unit.contains(
                "ExecStart=/opt/rpcnode/jdk/bin/java --enable-native-access=ALL-UNNAMED -jar /opt/rpcnode/lib/rpcnode-agent.jar",
            ),
        )
        assertTrue(unit.contains("EnvironmentFile=/etc/rpcnode/rpcnode-agent.env"))
        val env = AgentSystemInstall.envFileBody(
            port = 48990,
            tokenFile = java.nio.file.Path.of("/etc/rpcnode/agent.token"),
            rangeFile = java.nio.file.Path.of("/etc/rpcnode/rpcnode-agent.ports"),
            sysctlConf = java.nio.file.Path.of("/etc/sysctl.d/99-rpcnode-agent-ports.conf"),
        )
        assertEquals(
            """
            AGENT_PORT=48990
            AGENT_TOKEN_FILE=/etc/rpcnode/agent.token
            AGENT_RANGE_FILE=/etc/rpcnode/rpcnode-agent.ports
            AGENT_SYSCTL_CONF=/etc/sysctl.d/99-rpcnode-agent-ports.conf
            """.trimIndent() + "\n",
            env,
        )
    }

    @Test
    fun install_refuses_non_root()
    {
        val code = AgentSystemInstall.install(
            isRoot = { false },
            err = {},
            out = {},
            ensureHostDeps = {},
            waitHealthy = { _, _ -> true },
        )
        assertEquals(1, code)
    }

    @Test
    fun install_copies_jar_writes_token_and_enables_unit()
    {
        val root = Files.createTempDirectory("agent-install")
        val src = root.resolve("rpcnode-agent.jar")
        Files.writeString(src, "jar-bytes")
        val dest = root.resolve("opt")
        val etc = root.resolve("etc")
        val unitDir = root.resolve("systemd")
        Files.createDirectories(unitDir)
        val paths = AgentSystemInstall.Paths(
            destDir = dest,
            jarFile = dest.resolve("lib/rpcnode-agent.jar"),
            envFile = etc.resolve("rpcnode-agent.env"),
            tokenFile = etc.resolve("agent.token"),
            portFile = etc.resolve("rpcnode-agent.port"),
            rangeFile = etc.resolve("rpcnode-agent.ports"),
            unitPath = unitDir.resolve("rpcnode-agent.service"),
            sysctlConf = etc.resolve("99-rpcnode-agent-ports.conf"),
            unitName = "rpcnode-agent.service",
            port = 48990,
        )
        val ran = mutableListOf<List<String>>()
        val code = AgentSystemInstall.install(
            paths = paths,
            selfJar = src,
            javaBin = "/usr/bin/java",
            run = { cmd -> ran += cmd; 0 },
            out = {},
            err = {},
            isRoot = { true },
            ensureHostDeps = {},
            waitHealthy = { _, _ -> true },
            hostIp = { "10.0.0.5" },
            sleepMs = {},
        )
        assertEquals(0, code)
        assertTrue(Files.isRegularFile(paths.jarFile))
        assertEquals("jar-bytes", Files.readString(paths.jarFile))
        assertTrue(Files.readString(paths.envFile).contains("AGENT_PORT=48990"))
        assertTrue(Files.readString(paths.envFile).contains("AGENT_TOKEN_FILE=${paths.tokenFile}"))
        assertTrue(Files.isRegularFile(paths.tokenFile))
        assertTrue(Files.readString(paths.tokenFile).trim().length == 64)
        assertEquals("48990\n", Files.readString(paths.portFile))
        assertTrue(Files.readString(paths.unitPath).contains("-jar ${paths.jarFile}"))
        assertTrue(ran.any { it == listOf("systemctl", "enable", "rpcnode-agent.service") })
        assertTrue(ran.any { it == listOf("systemctl", "restart", "rpcnode-agent.service") })
    }

    @Test
    fun update_requires_existing_install()
    {
        val root = Files.createTempDirectory("agent-update-missing")
        val src = root.resolve("rpcnode-agent.jar")
        Files.writeString(src, "jar")
        val code = AgentSystemInstall.update(
            paths = AgentSystemInstall.Paths(
                destDir = root.resolve("opt"),
                jarFile = root.resolve("opt/lib/rpcnode-agent.jar"),
                envFile = root.resolve("etc/rpcnode-agent.env"),
                tokenFile = root.resolve("etc/agent.token"),
                portFile = root.resolve("etc/port"),
                rangeFile = root.resolve("etc/range"),
                unitPath = root.resolve("systemd/rpcnode-agent.service"),
                sysctlConf = root.resolve("etc/sysctl.conf"),
            ),
            selfJar = src,
            run = { 0 },
            out = {},
            err = {},
            isRoot = { true },
            ensureHostDeps = {},
            waitHealthy = { _, _ -> true },
            sleepMs = {},
        )
        assertEquals(1, code)
    }

    @Test
    fun install_keeps_existing_token()
    {
        val root = Files.createTempDirectory("agent-install-token")
        val src = root.resolve("rpcnode-agent.jar")
        Files.writeString(src, "jar")
        val tokenFile = root.resolve("etc/agent.token")
        Files.createDirectories(tokenFile.parent)
        Files.writeString(tokenFile, "keep-me-token\n")
        val paths = AgentSystemInstall.Paths(
            destDir = root.resolve("opt"),
            jarFile = root.resolve("opt/lib/rpcnode-agent.jar"),
            envFile = root.resolve("etc/rpcnode-agent.env"),
            tokenFile = tokenFile,
            portFile = root.resolve("etc/port"),
            rangeFile = root.resolve("etc/range"),
            unitPath = root.resolve("systemd/rpcnode-agent.service"),
            sysctlConf = root.resolve("etc/sysctl.conf"),
        )
        Files.createDirectories(paths.unitPath.parent)
        val code = AgentSystemInstall.install(
            paths = paths,
            selfJar = src,
            javaBin = "java",
            run = { 0 },
            out = {},
            err = {},
            isRoot = { true },
            ensureHostDeps = {},
            waitHealthy = { _, token ->
                assertEquals("keep-me-token", token)
                true
            },
            hostIp = { "127.0.0.1" },
            sleepMs = {},
        )
        assertEquals(0, code)
        assertEquals("keep-me-token", Files.readString(tokenFile).trim())
    }
}
