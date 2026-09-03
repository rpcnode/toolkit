package rpcnode.toolkit.agent.presentation.http

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
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import rpcnode.toolkit.agent.application.enroll.EnrollPanelUseCase
import rpcnode.toolkit.agent.application.enroll.ProbePanel
import rpcnode.toolkit.agent.application.enroll.UnenrollPanelUseCase
import rpcnode.toolkit.agent.application.metrics.CollectHostMetricsUseCase
import rpcnode.toolkit.agent.application.metrics.HostMetricsSource
import rpcnode.toolkit.agent.domain.model.HostDisk
import rpcnode.toolkit.agent.domain.model.HostMetrics
import rpcnode.toolkit.agent.infrastructure.enroll.InMemoryPanelEnrollmentStore

class AgentRoutesTest
{
    @Test
    fun identity_requires_the_agent_token() = testApplication {
        application {
            module(
                AgentConfig(
                    tokenFile = Path.of("unused"),
                    token = "secret",
                    version = "0.1.0",
                    port = AgentConfig.DEFAULT_PORT,
                    dev = true,
                ),
            )
        }

        val denied = client.get("/api/v1/agent")
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val ok = client.get("/api/v1/agent") {
            header(HttpHeaders.Authorization, "Bearer secret")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val body = Json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertEquals("rpcnode-agent", body["role"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", body["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun x_api_token_header_is_accepted() = testApplication {
        application { module(AgentConfig(tokenFile = Path.of("unused"), token = "secret", version = "0.1.0", port = AgentConfig.DEFAULT_PORT)) }
        val res = client.get("/healthz") {
            header("X-Api-Token", "secret")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertTrue(body["alive"]!!.jsonPrimitive.boolean)
        assertEquals("rpcnode-agent", body["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun metrics_lists_every_disk() = testApplication {
        val snap = HostMetrics(
            cpuPct = 12.5,
            load1 = 0.4,
            loadPct = 10.0,
            ncpu = 4,
            memPct = 40.0,
            memUsedMb = 8000.0,
            memTotalMb = 16000.0,
            disks = listOf(
                HostDisk("nvme0n1", "/", 100.0, 500.0, 80.0),
                HostDisk("nvme1n1", "/data", 1500.0, 2000.0, 25.0),
            ),
            os = "linux",
            arch = "amd64",
        )
        application {
            module(
                AgentConfig(tokenFile = Path.of("unused"), token = "secret", version = "0.1.0", port = AgentConfig.DEFAULT_PORT),
                CollectHostMetricsUseCase(HostMetricsSource { snap }),
            )
        }
        val res = client.get("/api/v1/metrics") {
            header(HttpHeaders.Authorization, "Bearer secret")
        }
        assertEquals(HttpStatusCode.OK, res.status)
        val cur = Json.parseToJsonElement(res.bodyAsText()).jsonObject["current"]!!.jsonObject
        assertEquals(12.5, cur["cpu_pct"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.4, cur["load_1"]!!.jsonPrimitive.content.toDouble())
        assertEquals(8000.0, cur["mem_used_mb"]!!.jsonPrimitive.content.toDouble())
        val disks = cur["disks"]!!.jsonArray
        assertEquals(2, disks.size)
        assertEquals("nvme0n1", disks[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("/", disks[0].jsonObject["mount"]!!.jsonPrimitive.content)
        assertEquals(2500.0, cur["disk_total_gb"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun enroll_stores_panel_destination() = testApplication {
        val store = InMemoryPanelEnrollmentStore()
        application {
            module(
                AgentConfig(tokenFile = Path.of("unused"), token = "secret", version = "0.1.0", port = AgentConfig.DEFAULT_PORT),
                enrollPanel = EnrollPanelUseCase(store, ProbePanel { true }),
            )
        }
        val denied = client.post("/api/v1/enroll") {
            contentType(ContentType.Application.Json)
            setBody("""{"panel_url":"http://10.0.0.2:8093","server_id":"srv-1"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val ok = client.post("/api/v1/enroll") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody("""{"panel_url":"http://10.0.0.2:8093/","server_id":"srv-1"}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val body = Json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertTrue(body["ok"]!!.jsonPrimitive.boolean)
        assertEquals("http://10.0.0.2:8093", body["panel_url"]!!.jsonPrimitive.content)
        assertEquals("srv-1", body["server_id"]!!.jsonPrimitive.content)
        assertEquals("/api/agent/v1/metrics", body["ingest_path"]!!.jsonPrimitive.content)
        assertEquals("0.1.0", body["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun enroll_fails_when_the_agent_cannot_reach_the_panel() = testApplication {
        val store = InMemoryPanelEnrollmentStore()
        application {
            module(
                AgentConfig(tokenFile = Path.of("unused"), token = "secret", version = "0.1.0", port = AgentConfig.DEFAULT_PORT),
                enrollPanel = EnrollPanelUseCase(store, ProbePanel { false }),
            )
        }
        val res = client.post("/api/v1/enroll") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody("""{"panel_url":"http://10.0.0.2:8093","server_id":"srv-1"}""")
        }
        assertEquals(HttpStatusCode.BadGateway, res.status)
        val body = Json.parseToJsonElement(res.bodyAsText()).jsonObject
        assertEquals("panel_unreachable", body["error"]!!.jsonPrimitive.content)
        assertEquals(null, runBlocking { store.read() })
    }

    @Test
    fun unenroll_clears_panel_destination() = testApplication {
        val store = InMemoryPanelEnrollmentStore()
        application {
            module(
                AgentConfig(tokenFile = Path.of("unused"), token = "secret", version = "0.1.0", port = AgentConfig.DEFAULT_PORT),
                enrollPanel = EnrollPanelUseCase(store, ProbePanel { true }),
                unenrollPanel = UnenrollPanelUseCase(store),
            )
        }
        client.post("/api/v1/enroll") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody("""{"panel_url":"http://10.0.0.2:8093","server_id":"srv-1"}""")
        }
        val denied = client.post("/api/v1/unenroll") {
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val ok = client.post("/api/v1/unenroll") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody("{}")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(null, runBlocking { store.read() })
    }

    @Test
    fun update_installs_when_channel_is_newer() = testApplication {
        var restarted = 0
        application {
            module(
                AgentConfig(tokenFile = Path.of("unused"), token = "secret", version = "0.1.0", port = AgentConfig.DEFAULT_PORT),
                updateAgent = rpcnode.toolkit.agent.application.update.UpdateAgentUseCase(
                    localVersion = "0.1.0",
                    resolvePanelUrl = { "http://10.0.0.2:8093" },
                    channel = rpcnode.toolkit.agent.application.update.AgentReleaseChannel { "0.1.1" },
                    installer = rpcnode.toolkit.agent.application.update.AgentJarInstaller {
                        rpcnode.toolkit.agent.application.update.AgentInstallResult.Ok("/opt/rpcnode/lib/rpcnode-agent.jar")
                    },
                    restarter = rpcnode.toolkit.agent.application.update.AgentRestarter { restarted += 1 },
                ),
            )
        }
        val denied = client.post("/api/v1/agent/update") {
            contentType(ContentType.Application.Json)
            setBody("""{"force":false}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, denied.status)

        val ok = client.post("/api/v1/agent/update") {
            header(HttpHeaders.Authorization, "Bearer secret")
            contentType(ContentType.Application.Json)
            setBody("""{"force":false}""")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
        val body = Json.parseToJsonElement(ok.bodyAsText()).jsonObject
        assertTrue(body["updated"]!!.jsonPrimitive.boolean)
        assertEquals("0.1.1", body["version"]!!.jsonPrimitive.content)
        assertEquals(1, restarted)
    }
}
