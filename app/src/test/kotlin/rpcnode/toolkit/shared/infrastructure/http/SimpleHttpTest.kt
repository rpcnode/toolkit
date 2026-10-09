package rpcnode.toolkit.shared.infrastructure.http

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SimpleHttpTest
{
    @Test
    fun getText_returns_body_on_2xx() = runTest {
        val engine = MockEngine {
            respond("12345", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val http = SimpleHttp(HttpClient(engine))
        assertEquals("12345", http.getText("https://example.test/tip"))
    }

    @Test
    fun postJson_returns_null_on_5xx() = runTest {
        val engine = MockEngine { respondError(HttpStatusCode.BadGateway) }
        val http = SimpleHttp(HttpClient(engine))
        assertNull(http.postJson("https://example.test/wallet/getnowblock"))
    }

    @Test
    fun postJson_sends_json_body() = runTest {
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Post, request.method)
            val body = request.body
            assertTrue(body is TextContent)
            assertEquals("{}", (body as TextContent).text)
            respond(
                """{"ok":true}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = SimpleHttp(HttpClient(engine))
        assertEquals("""{"ok":true}""", http.postJson("https://example.test/x"))
    }
}
