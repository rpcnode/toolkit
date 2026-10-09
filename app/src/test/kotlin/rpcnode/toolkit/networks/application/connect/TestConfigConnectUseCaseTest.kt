package rpcnode.toolkit.networks.application.connect

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.shared.infrastructure.http.SimpleHttp

class TestConfigConnectUseCaseTest
{
    @Test
    fun rejects_blank_or_non_http_url() = runTest {
        val uc = TestConfigConnectUseCase()
        assertIs<TestConfigConnectUseCase.Result.BadUrl>(uc("eth_rpc", ""))
        assertIs<TestConfigConnectUseCase.Result.BadUrl>(uc("eth_rpc", "ftp://x"))
    }

    @Test
    fun rejects_unknown_kind() = runTest {
        val uc = TestConfigConnectUseCase()
        assertIs<TestConfigConnectUseCase.Result.BadKind>(
            uc("nope", "https://example.com"),
        )
    }

    @Test
    fun tron_http_reports_code_version_and_height() = runTest {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when
            {
                path.endsWith("/wallet/getnodeinfo") ->
                    respond(
                        """{"configNodeInfo":{"codeVersion":"GreatVoyage-Nile-v4.8.2.1"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                path.endsWith("/wallet/getnowblock") ->
                    respond(
                        """{"block_header":{"raw_data":{"number":42}}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                else -> respond("nope", HttpStatusCode.NotFound)
            }
        }
        val uc = TestConfigConnectUseCase(SimpleHttp(HttpClient(engine)))
        val got = uc("tron_http", "http://192.168.0.59:18091")
        val ok = assertIs<TestConfigConnectUseCase.Result.Ok>(got)
        assertTrue(ok.detail.contains("GreatVoyage-Nile-v4.8.2.1"), ok.detail)
        assertTrue(ok.detail.contains("height 42"), ok.detail)
    }

    @Test
    fun tron_http_html_response_is_failed_not_json_exception() = runTest {
        val engine = MockEngine {
            respond(
                "<html><head><meta http-equiv=\"Content",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "text/html"),
            )
        }
        val uc = TestConfigConnectUseCase(SimpleHttp(HttpClient(engine)))
        val got = uc("tron_http", "http://192.168.0.59:18091")
        val failed = assertIs<TestConfigConnectUseCase.Result.Failed>(got)
        assertTrue(failed.detail.contains("wallet/getnodeinfo"), failed.detail)
        assertTrue(!failed.detail.contains("Unexpected JSON"), failed.detail)
    }
}
