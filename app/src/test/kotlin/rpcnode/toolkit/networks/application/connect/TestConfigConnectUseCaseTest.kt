package rpcnode.toolkit.networks.application.connect

import kotlin.test.Test
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

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
}
