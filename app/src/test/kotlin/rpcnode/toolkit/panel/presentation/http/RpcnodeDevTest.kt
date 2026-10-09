package rpcnode.toolkit.panel.presentation.http

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import rpcnode.toolkit.panel.testToolkit

class RpcnodeDevTest
{
    @Test
    fun inbound_line_marks_direction_and_peer()
    {
        assertEquals(
            "in GET /api/servers → 200 11ms from 127.0.0.1",
            rpcnode.toolkit.shared.infrastructure.log.HttpIoLog.inbound(
                "GET",
                "/api/servers",
                200,
                11,
                "127.0.0.1",
            ),
        )
    }

    @Test
    fun redact_secrets_keeps_other_fields()
    {
        val raw = """{"username":"admin","password":"secret-password","agent_key":"tok"}"""
        val out = redactSecrets(raw)
        assertTrue(out.contains("\"username\":\"admin\""))
        assertTrue(out.contains("\"password\":\"…\""))
        assertTrue(out.contains("\"agent_key\":\"…\""))
        assertFalse(out.contains("secret-password"))
    }

    @Test
    fun env_values()
    {
        assertTrue(rpcnodeDev("1"))
        assertTrue(rpcnodeDev("true"))
        assertTrue(rpcnodeDev("YES"))
        assertFalse(rpcnodeDev(null))
        assertFalse(rpcnodeDev(""))
        assertFalse(rpcnodeDev("0"))
        assertFalse(rpcnodeDev("false"))
    }

    @Test
    fun module_with_call_logging_still_serves_healthz() = testApplication {
        val dbPath = Files.createTempDirectory("dev-log").resolve("toolkit.db").toString()
        val cfg = ServerConfig(listen = "127.0.0.1", port = 8093, dbPath = dbPath, dev = true)
        application { module(cfg) }
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun dev_logging_still_accepts_json_body() = testApplication {
        val dbPath = Files.createTempDirectory("dev-log-post").resolve("toolkit.db").toString()
        val cfg = ServerConfig(listen = "127.0.0.1", port = 8093, dbPath = dbPath, dev = true)
        application { module(cfg, testToolkit()) }
        val created = client.post("/api/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"secret-password"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
    }
}
