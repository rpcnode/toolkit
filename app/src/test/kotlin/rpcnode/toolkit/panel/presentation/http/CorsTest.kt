package rpcnode.toolkit.panel.presentation.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.options
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CorsTest
{
    @Test
    fun allowed_origin_gets_cors_headers() = testApplication {
        val cfg = ServerConfig(
            corsOrigins = listOf("http://127.0.0.1:5173"),
        )
        application { module(cfg) }
        val response = client.get("/healthz") {
            header(HttpHeaders.Origin, "http://127.0.0.1:5173")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://127.0.0.1:5173", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", response.headers[HttpHeaders.AccessControlAllowCredentials])
    }

    @Test
    fun localhost_preflight_for_json_post() = testApplication {
        val cfg = ServerConfig(
            corsOrigins = listOf("http://127.0.0.1:5173", "http://localhost:5173"),
        )
        application { module(cfg) }
        val response = client.options("/api/setup") {
            header(HttpHeaders.Origin, "http://localhost:5173")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("http://localhost:5173", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", response.headers[HttpHeaders.AccessControlAllowCredentials])
        val allowHeaders = response.headers[HttpHeaders.AccessControlAllowHeaders].orEmpty()
        assertTrue(allowHeaders.contains("content-type", ignoreCase = true))
    }

    @Test
    fun localhost_matches_listed_loopback() = testApplication {
        val cfg = ServerConfig(
            corsOrigins = listOf("http://127.0.0.1:5173"),
        )
        application { module(cfg) }
        val response = client.options("/api/setup") {
            header(HttpHeaders.Origin, "http://localhost:5173")
            header(HttpHeaders.AccessControlRequestMethod, "POST")
            header(HttpHeaders.AccessControlRequestHeaders, "content-type")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
        assertEquals("http://localhost:5173", response.headers[HttpHeaders.AccessControlAllowOrigin])
    }

    @Test
    fun empty_allowlist_echoes_any_origin() = testApplication {
        val cfg = ServerConfig(
            corsOrigins = emptyList(),
        )
        application { module(cfg) }
        val response = client.get("/healthz") {
            header(HttpHeaders.Origin, "http://10.0.0.2:8093")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("http://10.0.0.2:8093", response.headers[HttpHeaders.AccessControlAllowOrigin])
        assertEquals("true", response.headers[HttpHeaders.AccessControlAllowCredentials])
    }

    @Test
    fun other_origin_has_no_allow_origin() = testApplication {
        val cfg = ServerConfig(
            corsOrigins = listOf("http://127.0.0.1:5173"),
        )
        application { module(cfg) }
        val response = client.get("/healthz") {
            header(HttpHeaders.Origin, "http://evil.example")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(null, response.headers[HttpHeaders.AccessControlAllowOrigin])
    }
}
