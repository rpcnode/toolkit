package rpcnode.toolkit.panel.install.presentation.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import rpcnode.toolkit.install.application.classpathAgentVersion
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.panel.presentation.http.module

class InstallRoutesTest
{
    @Test
    fun agent_sh_is_not_served_from_the_panel() = testApplication {
        val install = Files.createTempDirectory("install")
        val db = Files.createTempDirectory("db").resolve("toolkit.db").toString()
        application {
            module(ServerConfig(dbPath = db, installDir = install.toString()))
        }

        val res = client.get("/install/agent.sh")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun channel_points_at_the_agent_jar() = testApplication {
        val install = Files.createTempDirectory("install")
        val db = Files.createTempDirectory("db").resolve("toolkit.db").toString()
        application {
            module(ServerConfig(dbPath = db, installDir = install.toString()))
        }
        val res = client.get("/api/agent/channel")
        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("/install/binaries/rpcnode-agent.jar"))
        assertTrue(!res.bodyAsText().contains("/install/agent.sh"))
    }

    @Test
    fun install_path_serves_files_from_public_install() = testApplication {
        val install = Files.createTempDirectory("install")
        val binaries = install.resolve("binaries")
        Files.createDirectories(binaries)
        Files.writeString(binaries.resolve("sha256sums.txt"), "abc  rpcnode-agent.jar\n")
        val db = Files.createTempDirectory("db").resolve("toolkit.db").toString()
        application {
            module(ServerConfig(dbPath = db, installDir = install.toString()))
        }

        val res = client.get("/install/binaries/sha256sums.txt")
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("abc  rpcnode-agent.jar\n", res.bodyAsText())
    }

    @Test
    fun version_endpoint_is_the_gradle_chain_agent_version() = testApplication {
        val install = Files.createTempDirectory("install")
        val db = Files.createTempDirectory("db").resolve("toolkit.db").toString()
        application {
            module(ServerConfig(dbPath = db, installDir = install.toString()))
        }
        val res = client.get("/install/version")
        assertEquals(HttpStatusCode.OK, res.status)
        val expected = classpathAgentVersion()
        assertTrue(expected.isNotBlank())
        assertEquals(expected, res.bodyAsText().trim())
    }
}
