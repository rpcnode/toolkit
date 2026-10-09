package rpcnode.toolkit.panel.servers.presentation.http

import io.ktor.client.request.delete
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.panel.presentation.http.ServerConfig
import rpcnode.toolkit.panel.presentation.http.module
import rpcnode.toolkit.servers.FakeServerMetricsRepository
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerDisk
import rpcnode.toolkit.servers.domain.model.ServerId
import rpcnode.toolkit.servers.domain.model.ServerMetrics
import rpcnode.toolkit.panel.testToolkit

class ServersRoutesTest
{
    @Test
    fun list_includes_metrics_and_every_disk() = testApplication {
        val server = Server(
            id = ServerId.parse("srv-1")!!,
            name = "box",
            agentUrl = "http://10.0.0.5:48990",
            agentVersion = "0.1.0",
            createdAt = "t",
            updatedAt = "t",
        )
        val now = java.time.Instant.now().toString()
        application {
            module(
                ServerConfig(),
                testToolkit(
                    serverRepository = FakeServerRepository(listOf(server)),
                    serverMetricsRepository = FakeServerMetricsRepository(
                        listOf(
                            ServerMetrics(
                                serverId = server.id,
                                cpuPct = 12.5,
                                loadPct = 10.0,
                                ncpu = 4,
                                memPct = 40.0,
                                memUsedMb = 8000.0,
                                memTotalMb = 16000.0,
                                diskUsedPct = 36.0,
                                diskUsedGb = 900.0,
                                diskTotalGb = 2500.0,
                                load1 = 0.4,
                                disks = listOf(
                                    ServerDisk("nvme0n1", "/", 100.0, 500.0, 80.0),
                                    ServerDisk("nvme1n1", "/data", 1500.0, 2000.0, 25.0),
                                ),
                                os = "linux",
                                arch = "amd64",
                                collectedAt = now,
                                lastSeenAt = now,
                            ),
                        ),
                    ),
                ),
            )
        }
        val body = Json.parseToJsonElement(client.get("/api/servers").bodyAsText()).jsonObject
        val item = body["items"]!!.jsonArray[0].jsonObject
        assertEquals("box", item["name"]!!.jsonPrimitive.content)
        val metrics = item["metrics"]!!.jsonObject
        assertEquals("12.5", metrics["cpu_pct"]!!.jsonPrimitive.content)
        assertEquals("0.4", metrics["load_1"]!!.jsonPrimitive.content)
        val disks = metrics["disks"]!!.jsonArray
        assertEquals(2, disks.size)
        assertEquals("nvme0n1", disks[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("/data", disks[1].jsonObject["mount"]!!.jsonPrimitive.content)
        assertEquals("online", item["metrics_status"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", item["agent_version"]!!.jsonPrimitive.content)
    }

    @Test
    fun delete_queues_soft_remove() = testApplication {
        val server = Server(
            id = ServerId.parse("srv-1")!!,
            name = "box",
            agentUrl = "http://10.0.0.5:48990",
            createdAt = "t",
            updatedAt = "t",
        )
        val repo = FakeServerRepository(listOf(server))
        application {
            module(ServerConfig(), testToolkit(serverRepository = repo))
        }
        val res = client.delete("/api/servers/srv-1")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(true, body["queued"]!!.jsonPrimitive.boolean)
        assertEquals("removing", body["remove_status"]!!.jsonPrimitive.content)
    }

    @Test
    fun delete_unknown_server_is_not_found() = testApplication {
        application { module(ServerConfig(), testToolkit()) }
        val res = client.delete("/api/servers/missing")
        assertEquals(HttpStatusCode.NotFound, res.status)
    }

    @Test
    fun agent_push_writes_metrics_without_a_session() = testApplication {
        val server = Server(
            id = ServerId.parse("srv-1")!!,
            name = "box",
            agentUrl = "http://10.0.0.5:48990",
            agentKey = "tok",
            createdAt = "t",
            updatedAt = "t",
        )
        application {
            module(ServerConfig(), testToolkit(serverRepository = FakeServerRepository(listOf(server))))
        }
        val denied = client.post("/api/agent/v1/metrics") {
            contentType(ContentType.Application.Json)
            setBody("""{"cpu_pct":1.0,"server_id":"srv-1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val ok = client.post("/api/agent/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer tok")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {
                  "server_id":"srv-1",
                  "version":"0.1.1",
                  "cpu_pct":12.5,
                  "load_1":0.4,
                  "ncpu":4,
                  "mem_used_mb":8000,
                  "mem_total_mb":16000,
                  "disks":[
                    {"name":"nvme0n1","mount":"/","free_gb":100,"total_gb":500,"used_pct":80},
                    {"name":"nvme1n1","mount":"/data","free_gb":1500,"total_gb":2000,"used_pct":25}
                  ]
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val listed = Json.parseToJsonElement(client.get("/api/servers").bodyAsText()).jsonObject
        val metrics = listed["items"]!!.jsonArray[0].jsonObject["metrics"]!!.jsonObject
        assertEquals("12.5", metrics["cpu_pct"]!!.jsonPrimitive.content)
        assertEquals(2, metrics["disks"]!!.jsonArray.size)
        assertEquals("0.1.1", listed["items"]!!.jsonArray[0].jsonObject["agent_version"]!!.jsonPrimitive.content)
    }

    @Test
    fun update_agent_proxies_to_the_host() = testApplication {
        val server = Server(
            id = ServerId.parse("srv-1")!!,
            name = "box",
            agentUrl = "http://10.0.0.5:48990",
            agentKey = "tok",
            agentVersion = "0.1.0",
            createdAt = "t",
            updatedAt = "t",
        )
        val repo = FakeServerRepository(listOf(server))
        application {
            module(
                ServerConfig(),
                testToolkit(
                    serverRepository = repo,
                    updateHostAgent = rpcnode.toolkit.servers.application.probe.UpdateHostAgent { _, _, _ ->
                        rpcnode.toolkit.servers.application.probe.HostAgentUpdate(
                            ok = true,
                            updated = true,
                            version = "0.1.1",
                            remoteVersion = "0.1.1",
                            message = "installed",
                            status = 200,
                        )
                    },
                ),
            )
        }
        val res = client.post("/api/v1/agent/update?server=srv-1") {
            contentType(ContentType.Application.Json)
            setBody("""{"force":false}""")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals(true, body["updated"]!!.jsonPrimitive.boolean)
        assertEquals("0.1.1", body["version"]!!.jsonPrimitive.content)
        assertEquals("0.1.1", repo.find(server.id)!!.agentVersion)
    }
}
