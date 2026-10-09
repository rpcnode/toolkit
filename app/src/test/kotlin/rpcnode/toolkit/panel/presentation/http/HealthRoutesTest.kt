package rpcnode.toolkit.panel.presentation.http

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class HealthRoutesTest
{
    @Test
    fun healthz_json_shape() = testApplication {
        // Own temp file per run — a shared fixed path would rot across schema/migration changes.
        val dbPath = Files.createTempDirectory("healthz").resolve("toolkit.db").toString()
        val cfg = ServerConfig(listen = "127.0.0.1", port = 8093, dbPath = dbPath)
        application { module(cfg) }
        val response = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.decodeFromString<HealthzResponse>(response.bodyAsText())
        assertTrue(body.ok)
        assertTrue(body.alive)
        assertEquals("server", body.role)
        assertEquals(8093, body.port)
        assertEquals(dbPath, body.db)
    }
}
