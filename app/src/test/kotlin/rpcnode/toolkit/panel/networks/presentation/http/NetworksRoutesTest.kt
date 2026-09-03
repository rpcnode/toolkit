package rpcnode.toolkit.panel.networks.presentation.http

import io.ktor.client.request.delete
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.application.ClientFilesReadyChecker
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.domain.model.SnapshotArchive
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.panel.presentation.http.module
import rpcnode.toolkit.panel.testToolkit

class NetworksRoutesTest
{
    @Test
    fun get_without_all_is_empty_when_nothing_enabled() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val body = Json.parseToJsonElement(client.get("/api/networks").bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertTrue(body["items"]!!.jsonArray.isEmpty())
    }

    @Test
    fun enable_then_get_shows_it_even_without_files_on_disk() = testApplication {
        application { module(ServerConfig(), testToolkit()) }

        val enableRes = client.post("/api/networks") {
            contentType(ContentType.Application.Json)
            setBody("""{"network":"bitcoin","action":"enable"}""")
        }
        assertEquals(HttpStatusCode.OK, enableRes.status)
        val enableBody = Json.parseToJsonElement(enableRes.bodyAsText()).jsonObject
        assertEquals("ready", enableBody["status"]!!.jsonPrimitive.content)

        val body = Json.parseToJsonElement(client.get("/api/networks").bodyAsText()).jsonObject
        val items = body["items"]!!.jsonArray
        assertEquals(1, items.size)
        val bitcoin = items.single().jsonObject
        assertEquals("bitcoin", bitcoin["id"]!!.jsonPrimitive.content)
        assertTrue(bitcoin["enabled"]!!.jsonPrimitive.boolean)
        assertFalse(bitcoin["files_ready"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun get_with_all_lists_the_whole_catalog_even_when_not_enabled() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val body = Json.parseToJsonElement(client.get("/api/networks?all=1").bodyAsText()).jsonObject
        val items = body["items"]!!.jsonArray
        assertEquals(17, items.size)
        val ids = items.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("arb", "base", "bch", "bitcoin", "bsc", "dash", "doge", "ethereum", "hyperliquid", "ltc", "polygon", "solana", "sui", "ton", "tron", "xrpl", "zcash"), ids)
        for (item in items)
        {
            assertFalse(item.jsonObject["enabled"]!!.jsonPrimitive.boolean)
            assertEquals("", item.jsonObject["status"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun get_with_all_substitutes_in_the_shipped_yaml_facts_for_bitcoin() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val body = Json.parseToJsonElement(client.get("/api/networks?all=1").bodyAsText()).jsonObject
        val bitcoin = body["items"]!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == "bitcoin" }.jsonObject

        val envDetails = bitcoin["env_details"]!!.jsonArray
        assertEquals(4, envDetails.size)
        val mainnet = envDetails.single { it.jsonObject["id"]!!.jsonPrimitive.content == "mainnet" }.jsonObject
        assertEquals(1024.0, mainnet["disk_hint_gib"]!!.jsonPrimitive.content.toDouble())
        assertEquals(4.0, mainnet["cpu_cores"]!!.jsonPrimitive.content.toDouble())
        assertEquals(16.0, mainnet["memory_gib"]!!.jsonPrimitive.content.toDouble())

        val diskRoles = bitcoin["disk_roles"]!!.jsonArray
        assertEquals(2, diskRoles.size)
        assertTrue(bitcoin["disk_notes"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun get_with_all_substitutes_in_the_shipped_yaml_facts_for_tron() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val body = Json.parseToJsonElement(client.get("/api/networks?all=1").bodyAsText()).jsonObject
        val tron = body["items"]!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == "tron" }.jsonObject

        val envDetails = tron["env_details"]!!.jsonArray
        assertEquals(3, envDetails.size)
        val mainnet = envDetails.single { it.jsonObject["id"]!!.jsonPrimitive.content == "mainnet" }.jsonObject
        assertEquals(4096.0, mainnet["disk_hint_gib"]!!.jsonPrimitive.content.toDouble())
        assertEquals(2900.0, mainnet["full_node_gib"]!!.jsonPrimitive.content.toDouble())
        assertEquals(3600.0, mainnet["archive_gib"]!!.jsonPrimitive.content.toDouble())
        assertEquals("required", mainnet["snapshot"]!!.jsonPrimitive.content)

        assertEquals(2, tron["disk_roles"]!!.jsonArray.size)
    }

    @Test
    fun get_with_all_includes_polygon_client_config_bindings() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val body = Json.parseToJsonElement(client.get("/api/networks?all=1").bodyAsText()).jsonObject
        val polygon = body["items"]!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == "polygon" }.jsonObject
        val cfg = polygon["client_config"]!!.jsonObject
        assertEquals("bor", cfg["program"]!!.jsonPrimitive.content)
        assertEquals("flags", cfg["format"]!!.jsonPrimitive.content)
        assertEquals(6, cfg["bindings"]!!.jsonArray.size)
    }

    @Test
    fun delete_removes_an_enabled_network() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        client.post("/api/networks") {
            contentType(ContentType.Application.Json)
            setBody("""{"network":"bitcoin","action":"enable"}""")
        }
        val deleteRes = client.delete("/api/networks/bitcoin")
        assertEquals(HttpStatusCode.OK, deleteRes.status)

        val afterDelete = Json.parseToJsonElement(client.get("/api/networks").bodyAsText()).jsonObject
        assertTrue(afterDelete["items"]!!.jsonArray.isEmpty())
    }

    @Test
    fun install_check_reports_client_required_when_files_are_missing() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val res = client.post("/api/networks/install") {
            contentType(ContentType.Application.Json)
            setBody("""{"network":"bitcoin"}""")
        }
        assertEquals(HttpStatusCode.Conflict, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("client_required", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun install_check_reports_files_ok_when_files_are_on_disk() = testApplication {
        val toolkit = testToolkit(clientFilesReady = ClientFilesReadyChecker { _, _ -> true })
        application { module(ServerConfig(), toolkit) }
        val res = client.post("/api/networks/install") {
            contentType(ContentType.Application.Json)
            setBody("""{"network":"bitcoin"}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("files_ok", body["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun snapshot_archive_is_one_env_and_not_on_the_list() = testApplication {
        val toolkit = testToolkit(
            snapshotResolvers = mapOf(
                NetworkId.TRON to SnapshotResolver { env, _ ->
                    if (env == EnvId.MAINNET)
                    {
                        SnapshotArchive(
                            url = "https://mirror.example/latest.tgz",
                            streamUnpack = true,
                            sizeBytes = 4096,
                        )
                    }
                    else
                    {
                        null
                    }
                },
            ),
        )
        application { module(ServerConfig(), toolkit) }

        val list = Json.parseToJsonElement(client.get("/api/networks?all=1").bodyAsText()).jsonObject
        val tron = list["items"]!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == "tron" }.jsonObject
        val mainnet = tron["env_details"]!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == "mainnet" }.jsonObject
        assertFalse(mainnet.containsKey("snapshot_url"))

        val resolved = Json.parseToJsonElement(
            client.get("/api/networks/snapshot?network=tron&env=mainnet").bodyAsText(),
        ).jsonObject
        assertTrue(resolved["ok"]!!.jsonPrimitive.boolean)
        assertEquals("https://mirror.example/latest.tgz", resolved["url"]!!.jsonPrimitive.content)
        assertEquals("https://mirror.example/latest.tgz", resolved["official_url"]!!.jsonPrimitive.content)
        assertEquals("official", resolved["source"]!!.jsonPrimitive.content)
        assertTrue(resolved["stream_unpack"]!!.jsonPrimitive.boolean)
        assertEquals(4096, resolved["size_bytes"]!!.jsonPrimitive.content.toLong())

        val nile = Json.parseToJsonElement(
            client.get("/api/networks/snapshot?network=tron&env=nile").bodyAsText(),
        ).jsonObject
        assertTrue(nile["ok"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, nile["url"])
        assertEquals(JsonNull, nile["stream_unpack"])
        assertEquals(JsonNull, nile["size_bytes"])

        val unknown = client.get("/api/networks/snapshot?network=does-not-exist&env=mainnet")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)
    }

    @Test
    fun unknown_network_action_returns_bad_request() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val res = client.post("/api/networks") {
            contentType(ContentType.Application.Json)
            setBody("""{"network":"does-not-exist","action":"enable"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
    }
}
