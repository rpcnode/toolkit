package rpcnode.toolkit.panel.clients.presentation.http

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientProgramCatalog
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.FakeGitHubTokenProvider
import rpcnode.toolkit.clients.domain.model.ClientArtifactRole
import rpcnode.toolkit.clients.domain.model.ClientArtifactSpec
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.clients.domain.model.ClientRelease
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.panel.presentation.http.module
import rpcnode.toolkit.panel.testToolkit

class ClientsRoutesTest
{
    @Test
    fun github_token_set_is_false_when_no_token_is_configured() = testApplication {
        application { module(ServerConfig(), testToolkit(githubTokenProvider = FakeGitHubTokenProvider(null))) }
        val absent = Json.parseToJsonElement(client.get("/api/clients").bodyAsText()).jsonObject
        assertFalse(absent["github_token_set"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun github_token_set_is_true_once_a_token_is_configured() = testApplication {
        application { module(ServerConfig(), testToolkit(githubTokenProvider = FakeGitHubTokenProvider("a-real-token"))) }
        val body = Json.parseToJsonElement(client.get("/api/clients").bodyAsText()).jsonObject
        assertTrue(body["github_token_set"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun list_is_empty_when_nothing_has_ever_been_synced() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val body = Json.parseToJsonElement(client.get("/api/clients").bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertTrue(body["rows"]!!.jsonArray.isEmpty())
    }

    @Test
    fun a_synced_program_shows_up_with_snake_case_wire_fields() = testApplication {
        val versionRepository = FakeClientVersionRepository(
            seed = listOf(
                ClientVersionPin(
                    network = NetworkId.BITCOIN,
                    env = EnvId.MAINNET,
                    program = "bitcoin",
                    currentVersion = "29.4",
                    latestVersion = "29.4",
                ),
            ),
        )
        val programCatalog = FakeClientProgramCatalog(
            listOf(
                ClientProgramSpec(
                    network = NetworkId.BITCOIN,
                    env = EnvId.MAINNET,
                    programId = "bitcoin",
                    source = ClientVersionSource.GitHubRelease(repo = "bitcoin/bitcoin"),
                    artifacts = listOf(ClientArtifactSpec("bitcoin.tar.gz", ClientArtifactRole.ARTIFACT, "https://example.com/{version}")),
                ),
            ),
        )
        application {
            module(ServerConfig(), testToolkit(clientVersionRepository = versionRepository, clientProgramCatalog = programCatalog))
        }

        val body = Json.parseToJsonElement(client.get("/api/clients").bodyAsText()).jsonObject
        val row = body["rows"]!!.jsonArray.single().jsonObject
        assertEquals("bitcoin", row["network"]!!.jsonPrimitive.content)
        assertEquals("mainnet", row["env"]!!.jsonPrimitive.content)
        assertEquals("29.4", row["pin"]!!.jsonPrimitive.content)
        assertEquals("ok", row["status"]!!.jsonPrimitive.content)
        assertEquals(1, body["stats"]!!.jsonObject["total"]!!.jsonPrimitive.int)
    }

    @Test
    fun preview_requires_both_network_and_env() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val res = client.get("/api/clients/preview?network=bitcoin")
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun add_without_a_token_reports_need_token() = testApplication {
        application { module(ServerConfig(), testToolkit(githubTokenProvider = FakeGitHubTokenProvider(null))) }
        val res = client.post("/api/clients") {
            contentType(ContentType.Application.Json)
            setBody("""{"network":"bitcoin","env":"mainnet"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("need_token", body["probe"]!!.jsonPrimitive.content)
    }

    @Test
    fun add_with_a_token_queues_a_probe() = testApplication {
        application { module(ServerConfig(), testToolkit(githubTokenProvider = FakeGitHubTokenProvider("a-real-token"))) }
        val res = client.post("/api/clients") {
            contentType(ContentType.Application.Json)
            setBody("""{"network":"bitcoin","env":"mainnet"}""")
        }
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("queued", body["probe"]!!.jsonPrimitive.content)
    }

    @Test
    fun add_rejects_an_unknown_network() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val res = client.post("/api/clients") {
            contentType(ContentType.Application.Json)
            setBody("""{"network":"does-not-exist","env":"mainnet"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun probe_without_a_token_returns_409_github_token_required() = testApplication {
        application { module(ServerConfig(), testToolkit(githubTokenProvider = FakeGitHubTokenProvider(null))) }
        val res = client.post("/api/clients/probe") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("github_token_required", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun probe_with_a_token_waits_until_latest_is_written() = testApplication {
        application { module(ServerConfig(), testToolkit(githubTokenProvider = FakeGitHubTokenProvider("a-real-token"))) }
        val res = client.post("/api/clients/probe") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, res.status)
    }

    @Test
    fun sync_without_a_token_returns_409_github_token_required() = testApplication {
        application { module(ServerConfig(), testToolkit(githubTokenProvider = FakeGitHubTokenProvider(null))) }
        val res = client.post("/api/clients/sync") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
    }

    @Test
    fun version_is_one_env_from_the_network_resolver() = testApplication {
        val toolkit = testToolkit(
            clientReleaseResolvers = mapOf(
                NetworkId.BITCOIN to ClientReleaseResolver { env ->
                    if (env == EnvId.MAINNET)
                    {
                        ClientRelease(version = "29.4", tag = "v29.4", sourceLabel = "bitcoin/bitcoin")
                    }
                    else
                    {
                        null
                    }
                },
            ),
        )
        application { module(ServerConfig(), toolkit) }

        val mainnet = Json.parseToJsonElement(
            client.get("/api/clients/version?network=bitcoin&env=mainnet").bodyAsText(),
        ).jsonObject
        assertTrue(mainnet["ok"]!!.jsonPrimitive.boolean)
        assertEquals("29.4", mainnet["version"]!!.jsonPrimitive.content)
        assertEquals("v29.4", mainnet["tag"]!!.jsonPrimitive.content)
        assertEquals("bitcoin/bitcoin", mainnet["source"]!!.jsonPrimitive.content)

        val signet = Json.parseToJsonElement(
            client.get("/api/clients/version?network=bitcoin&env=signet").bodyAsText(),
        ).jsonObject
        assertTrue(signet["ok"]!!.jsonPrimitive.boolean)
        assertEquals(null, signet["version"]?.jsonPrimitive?.contentOrNull)

        val unknown = client.get("/api/clients/version?network=does-not-exist&env=mainnet")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
    }

    @Test
    fun delete_requires_a_network() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val res = client.post("/api/clients/delete") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }

    @Test
    fun delete_wipes_the_network_and_reports_it_purged() = testApplication {
        val versionRepository = FakeClientVersionRepository(
            seed = listOf(ClientVersionPin(NetworkId.BITCOIN, EnvId.MAINNET, "bitcoin", currentVersion = "29.4")),
        )
        application { module(ServerConfig(), testToolkit(clientVersionRepository = versionRepository)) }
        val res = client.post("/api/clients/delete") {
            contentType(ContentType.Application.Json)
            setBody("""{"network":"bitcoin"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(body["purged"]!!.jsonPrimitive.boolean)
        assertTrue((body["dest"]?.jsonPrimitive?.contentOrNull ?: "").isNotEmpty())
    }
}
