package rpcnode.toolkit.panel.settings.presentation.http

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.panel.presentation.http.module
import rpcnode.toolkit.settings.application.save.GitHubTokenCheck
import rpcnode.toolkit.settings.application.save.GitHubTokenChecker
import rpcnode.toolkit.panel.testToolkit

class SettingsRoutesTest
{
    @Test
    fun get_is_public() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val body = Json.parseToJsonElement(client.get("/api/settings").bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertFalse(body["configured"]!!.jsonPrimitive.boolean)
        assertEquals("http://127.0.0.1:8094", body["presets"]!!.jsonObject["local"]!!.jsonPrimitive.content)
    }

    @Test
    fun put_requires_session() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val res = client.put("/api/settings") {
            contentType(ContentType.Application.Json)
            setBody("""{"install_origin":"http://127.0.0.1:8093"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, res.status)
    }

    @Test
    fun put_saves_origin() = testApplication {
        val toolkit = testToolkit()
        application { module(ServerConfig(), toolkit) }
        val token = setupAdmin(client)
        val res = client.put("/api/settings") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"install_origin":"https://toolkit.rpcnode.dev"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(body["configured"]!!.jsonPrimitive.boolean)
        assertEquals("https://toolkit.rpcnode.dev", body["install_origin"]!!.jsonPrimitive.content)
        assertEquals(
            "https://toolkit.rpcnode.dev/install/binaries/rpcnode-agent.jar",
            body["agent_download_url"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun put_rejects_github_token() = testApplication {
        val toolkit = testToolkit(githubChecker = GitHubTokenChecker { GitHubTokenCheck.Rejected })
        application { module(ServerConfig(), toolkit) }
        val token = setupAdmin(client)
        val res = client.put("/api/settings") {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody("""{"github_token":"ghp_nope"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("github_token_invalid", body["error"]!!.jsonPrimitive.content)
    }

    private suspend fun setupAdmin(client: HttpClient): String
    {
        val created = client.post("/api/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"username":"admin","password":"secret-password"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        return Json.parseToJsonElement(created.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
    }
}
