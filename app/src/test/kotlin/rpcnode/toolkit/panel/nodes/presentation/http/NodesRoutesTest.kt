package rpcnode.toolkit.panel.nodes.presentation.http

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.FakeClientProgramCatalog
import rpcnode.toolkit.clients.FakeClientVersionRepository
import rpcnode.toolkit.clients.domain.model.ClientProgramSpec
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.model.ClientVersionSource
import rpcnode.toolkit.clients.domain.model.ProgramPort
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.application.disks.HostDiskReader
import rpcnode.toolkit.nodes.domain.model.HostBlockDevice
import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog
import rpcnode.toolkit.nodes.domain.model.HostMount
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.panel.presentation.http.module
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.application.probe.AgentPortCheck
import rpcnode.toolkit.servers.application.probe.CheckAgentPorts
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.panel.testToolkit

class NodesRoutesTest
{
    private val server = Server(
        id = ServerId.parse("srv-1")!!,
        name = "box",
        agentUrl = "http://127.0.0.1:38990",
        createdAt = "t",
        updatedAt = "t",
    )

    private val serverWithAgent = server.copy(agentKey = "tok")

    private val hostDiskCatalog = HostDiskCatalog(
        disks = listOf(
            HostBlockDevice(name = "nvme0n1", tran = "nvme", preferred = true),
            HostBlockDevice(name = "nvme1n1", tran = "nvme", preferred = true),
        ),
        mounts = listOf(
            HostMount(target = "/mnt/data1", availBytes = 1_500_000_000_000, tran = "nvme", preferred = true),
            HostMount(target = "/mnt/data2", availBytes = 1_500_000_000_000, tran = "nvme", preferred = true),
        ),
        unused = emptyList(),
    )

    private fun toolkitWithDisks(nodeRepository: FakeNodeRepository = FakeNodeRepository()) = testToolkit(
        serverRepository = FakeServerRepository(listOf(serverWithAgent)),
        nodeRepository = nodeRepository,
        clientVersionRepository = FakeClientVersionRepository(
            listOf(
                ClientVersionPin(
                    network = NetworkId.TRON,
                    env = EnvId.MAINNET,
                    program = "FullNode.jar",
                    currentVersion = "v1",
                ),
            ),
        ),
        hostDiskReader = HostDiskReader { _, _ -> hostDiskCatalog },
    )

    private fun toolkitWithClient() = testToolkit(
        serverRepository = FakeServerRepository(listOf(serverWithAgent)),
        clientVersionRepository = FakeClientVersionRepository(
            listOf(
                ClientVersionPin(
                    network = NetworkId.TRON,
                    env = EnvId.MAINNET,
                    program = "FullNode.jar",
                    currentVersion = "v1",
                ),
            ),
        ),
    )

    @Test
    fun list_is_empty_before_any_node() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val body = Json.parseToJsonElement(client.get("/api/nodes").bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertEquals(0, body["items"]!!.jsonArray.size)
    }

    @Test
    fun post_persists_awaiting_ports_and_get_returns_it() = testApplication {
        application { module(ServerConfig(), toolkitWithClient()) }
        val created = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody("""{"server_id":"srv-1","network":"tron","env":"mainnet"}""")
        }
        assertEquals(HttpStatusCode.OK, created.status)
        val item = Json.parseToJsonElement(created.bodyAsText()).jsonObject["item"]!!.jsonObject
        assertEquals("tron", item["network"]!!.jsonPrimitive.content)
        assertEquals("mainnet", item["env"]!!.jsonPrimitive.content)
        assertEquals("awaiting_ports", item["status"]!!.jsonPrimitive.content)
        assertEquals(0, item["public_port"]!!.jsonPrimitive.int)
        assertTrue(item["needs_snapshot"]!!.jsonPrimitive.boolean)

        val id = item["id"]!!.jsonPrimitive.content
        val listed = Json.parseToJsonElement(client.get("/api/nodes").bodyAsText()).jsonObject
        assertEquals(1, listed["items"]!!.jsonArray.size)

        val one = Json.parseToJsonElement(client.get("/api/nodes/$id").bodyAsText()).jsonObject
        assertEquals(id, one["item"]!!.jsonObject["id"]!!.jsonPrimitive.content)
    }

    @Test
    fun post_without_a_client_is_rejected() = testApplication {
        application {
            module(ServerConfig(), testToolkit(serverRepository = FakeServerRepository(listOf(server))))
        }
        val res = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody("""{"server_id":"srv-1","network":"tron","env":"mainnet"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("no_client", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun duplicate_is_conflict() = testApplication {
        application { module(ServerConfig(), toolkitWithClient()) }
        val first = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody("""{"server_id":"srv-1","network":"tron","env":"mainnet"}""")
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val second = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody("""{"server_id":"srv-1","network":"tron","env":"mainnet"}""")
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
    }

    @Test
    fun remove_panel_mode_drops_the_row() = testApplication {
        application { module(ServerConfig(), toolkitWithClient()) }
        val created = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody("""{"server_id":"srv-1","network":"tron","env":"mainnet"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText())
            .jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content

        val removed = client.post("/api/nodes/remove") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","mode":"panel","delete_files":false}""")
        }
        assertEquals(HttpStatusCode.OK, removed.status)
        val body = Json.parseToJsonElement(removed.bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)

        val listed = Json.parseToJsonElement(client.get("/api/nodes").bodyAsText()).jsonObject
        assertEquals(0, listed["items"]!!.jsonArray.size)
    }

    @Test
    fun remove_agents_mode_removes_row_via_host() = testApplication {
        application { module(ServerConfig(), toolkitWithClient()) }
        val created = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody("""{"server_id":"srv-1","network":"tron","env":"mainnet"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText())
            .jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content

        val res = client.post("/api/nodes/remove") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"$id","mode":"agents","delete_files":false}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)

        val listed = Json.parseToJsonElement(client.get("/api/nodes").bodyAsText()).jsonObject
        assertEquals(0, listed["items"]!!.jsonArray.size)
    }

    @Test
    fun get_ports_returns_catalog_without_live_status() = testApplication {
        val catalog = FakeClientProgramCatalog(
            listOf(
                ClientProgramSpec(
                    network = NetworkId.TRON,
                    env = EnvId.MAINNET,
                    programId = "FullNode.jar",
                    source = ClientVersionSource.Pinned(version = "1", tag = "v1", label = "test"),
                    ports = listOf(
                        ProgramPort(role = "p2p", port = 18888, label = "P2P"),
                        ProgramPort(role = "http_fullnode", port = 18090, label = "HTTP API (fullnode)"),
                    ),
                ),
            ),
        )
        val toolkit = testToolkit(
            serverRepository = FakeServerRepository(listOf(server)),
            clientVersionRepository = FakeClientVersionRepository(
                listOf(
                    ClientVersionPin(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar", currentVersion = "v1"),
                ),
            ),
            clientProgramCatalog = catalog,
        )
        application { module(ServerConfig(), toolkit) }
        val created = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody("""{"server_id":"srv-1","network":"tron","env":"mainnet"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText())
            .jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content

        val res = client.get("/api/nodes/$id/ports")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        val items = body["items"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals(18888, items[0].jsonObject["port"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, items[0].jsonObject["free"])
        assertEquals("http://127.0.0.1:18090", body["endpoint"]!!.jsonPrimitive.content)
    }

    @Test
    fun post_ports_check_merges_catalog_with_agent_check() = testApplication {
        val catalog = FakeClientProgramCatalog(
            listOf(
                ClientProgramSpec(
                    network = NetworkId.TRON,
                    env = EnvId.MAINNET,
                    programId = "FullNode.jar",
                    source = ClientVersionSource.Pinned(version = "1", tag = "v1", label = "test"),
                    ports = listOf(
                        ProgramPort(role = "p2p", port = 18888, label = "P2P"),
                        ProgramPort(role = "http_fullnode", port = 18090, label = "HTTP API (fullnode)"),
                    ),
                ),
            ),
        )
        val toolkit = testToolkit(
            serverRepository = FakeServerRepository(listOf(serverWithAgent)),
            clientVersionRepository = FakeClientVersionRepository(
                listOf(
                    ClientVersionPin(NetworkId.TRON, EnvId.MAINNET, "FullNode.jar", currentVersion = "v1"),
                ),
            ),
            clientProgramCatalog = catalog,
            checkAgentPorts = CheckAgentPorts { _, _, _ ->
                listOf(
                    AgentPortCheck(port = 18888, free = true),
                    AgentPortCheck(port = 18090, free = false, holder = "FullNode.jar"),
                )
            },
        )
        application { module(ServerConfig(), toolkit) }
        val res = client.post("/api/host/ports/check?server_id=srv-1&network=tron&env=mainnet")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        val items = body["items"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals(18888, items[0].jsonObject["port"]!!.jsonPrimitive.int)
        assertTrue(items[0].jsonObject["free"]!!.jsonPrimitive.boolean)
        assertEquals("FullNode.jar", items[1].jsonObject["holder"]!!.jsonPrimitive.content)
        assertEquals("http://127.0.0.1:18090", body["endpoint"]!!.jsonPrimitive.content)
    }

    @Test
    fun ports_unknown_node_is_not_found() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val res = client.get("/api/nodes/missing/ports")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun remove_unknown_id_is_not_found() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val res = client.post("/api/nodes/remove") {
            contentType(ContentType.Application.Json)
            setBody("""{"id":"missing","mode":"panel"}""")
        }
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun host_disks_returns_inventory_from_agent() = testApplication {
        application { module(ServerConfig(), toolkitWithDisks()) }
        val res = client.get("/api/host/disks?server_id=srv-1")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertEquals(2, body["disks"]!!.jsonArray.size)
        assertEquals(2, body["mounts"]!!.jsonArray.size)
        assertTrue(body["summary"]!!.jsonPrimitive.content.isNotBlank())
    }

    @Test
    fun disk_layout_put_then_get_round_trips() = testApplication {
        val nodes = FakeNodeRepository()
        application { module(ServerConfig(), toolkitWithDisks(nodes)) }
        val created = client.post("/api/nodes") {
            contentType(ContentType.Application.Json)
            setBody("""{"server_id":"srv-1","network":"tron","env":"mainnet"}""")
        }
        val id = Json.parseToJsonElement(created.bodyAsText())
            .jsonObject["item"]!!.jsonObject["id"]!!.jsonPrimitive.content
        val layout = """{"strategy":"single","network":"tron","env":"mainnet","roles":[]}"""

        val saved = client.put("/api/nodes/$id/disk-layout") {
            contentType(ContentType.Application.Json)
            setBody("""{"disk_layout":$layout}""")
        }
        assertEquals(HttpStatusCode.OK, saved.status)

        val got = client.get("/api/nodes/$id/disk-layout")
        assertEquals(HttpStatusCode.OK, got.status)
        val body = Json.parseToJsonElement(got.bodyAsText()).jsonObject
        assertEquals("single", body["disk_layout"]!!.jsonObject["strategy"]!!.jsonPrimitive.content)
        assertEquals(2, body["multi_disk_roles"]!!.jsonArray.size)
        assertEquals("fullnode", body["multi_disk_roles"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertTrue(body["layout_rules"]!!.jsonArray.size >= 1)
        val roles = body["disk_layout"]!!.jsonObject["roles"]!!.jsonArray
        assertEquals(2, roles.size)

        val node = Json.parseToJsonElement(client.get("/api/nodes/$id").bodyAsText())
            .jsonObject["item"]!!.jsonObject
        assertEquals("single", node["disk_layout"]!!.jsonObject["strategy"]!!.jsonPrimitive.content)
    }
}
