package rpcnode.toolkit.panel.auth.presentation.http

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.panel.presentation.http.module
import rpcnode.toolkit.panel.testToolkit

class AuthRoutesTest
{
    @Test
    fun setup_then_auth_status() = testApplication {
        val toolkit = testToolkit()
        application { module(ServerConfig(), toolkit) }
        val setup0 = Json.parseToJsonElement(client.get("/api/setup/status").bodyAsText()).jsonObject
        assertTrue(setup0["needed"]!!.jsonPrimitive.boolean)

        val created = client.post("/api/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"secret-password"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val cookie = created.headers[HttpHeaders.SetCookie].orEmpty()
        assertTrue(cookie.contains("rpcnode_session="))
        assertTrue(cookie.contains("Max-Age="))

        val setup1 = Json.parseToJsonElement(client.get("/api/setup/status").bodyAsText()).jsonObject
        assertEquals(false, setup1["needed"]!!.jsonPrimitive.boolean)

        val status1 = Json.parseToJsonElement(
            client.get("/api/auth/status") {
                header(HttpHeaders.Cookie, cookie.substringBefore(';'))
            }.bodyAsText(),
        ).jsonObject
        assertEquals(true, status1["authenticated"]!!.jsonPrimitive.boolean)
        assertEquals("admin", status1["user"]!!.jsonPrimitive.content)
    }

    @Test
    fun bearer_token_authenticates_without_cookie() = testApplication {
        val toolkit = testToolkit()
        application { module(ServerConfig(), toolkit) }
        val created = client.post("/api/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"secret-password"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val token = Json.parseToJsonElement(created.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
        assertTrue(token.isNotBlank())

        val status = Json.parseToJsonElement(
            client.get("/api/auth/status") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }.bodyAsText(),
        ).jsonObject
        assertEquals(true, status["authenticated"]!!.jsonPrimitive.boolean)
        assertEquals("admin", status["user"]!!.jsonPrimitive.content)
    }
}
