package rpcnode.toolkit.proxies

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.nodes.application.proxy.SetNodeProxyResult
import rpcnode.toolkit.nodes.application.proxy.SetNodeProxyUseCase
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.proxies.application.DeleteProxyResult
import rpcnode.toolkit.proxies.application.DeleteProxyUseCase
import rpcnode.toolkit.proxies.application.ListProxiesUseCase
import rpcnode.toolkit.proxies.application.ResolveNodeProxyUrl
import rpcnode.toolkit.proxies.application.SaveProxyResult
import rpcnode.toolkit.proxies.application.SaveProxyUseCase
import rpcnode.toolkit.proxies.application.TestProxyUseCase
import rpcnode.toolkit.proxies.domain.model.ProxyCheck
import rpcnode.toolkit.proxies.domain.model.ProxyCheckRunner
import rpcnode.toolkit.proxies.domain.model.ProxyUrl
import rpcnode.toolkit.servers.domain.model.ServerId

class ProxiesTest
{
    @Test
    fun only_http_proxies_with_host_and_port_parse_and_the_password_is_masked()
    {
        val ok = assertIs<ProxyUrl.Parse.Ok>(ProxyUrl.parse(" http://bob:s3cret@10.0.0.5:3128/ "))
        assertEquals("http://bob:s3cret@10.0.0.5:3128", ok.url.value)
        assertEquals("http://bob:***@10.0.0.5:3128", ok.url.masked)
        assertEquals("http://10.0.0.5:3128", (ProxyUrl.parse("http://10.0.0.5:3128") as ProxyUrl.Parse.Ok).url.masked)

        assertEquals(ProxyUrl.Parse.Empty, ProxyUrl.parse("  "))
        assertIs<ProxyUrl.Parse.Invalid>(ProxyUrl.parse("socks5://10.0.0.5:1080"))
        assertIs<ProxyUrl.Parse.Invalid>(ProxyUrl.parse("http://10.0.0.5"))
        assertIs<ProxyUrl.Parse.Invalid>(ProxyUrl.parse("http://10.0.0.5:3128/path"))
    }

    @Test
    fun an_edit_that_sends_back_the_masked_url_keeps_the_stored_password() = runTest {
        val repo = FakeProxyRepository()
        val save = SaveProxyUseCase(repo)
        val created = assertIs<SaveProxyResult.Ok>(save(null, "", "http://bob:s3cret@proxy.example:3128")).proxy
        assertEquals("proxy.example:3128", created.name)

        val renamed = assertIs<SaveProxyResult.Ok>(save(created.id.value, "EU", "http://bob:***@proxy.example:3128")).proxy
        assertEquals("EU", renamed.name)
        assertEquals("http://bob:s3cret@proxy.example:3128", renamed.url.value)

        val moved = assertIs<SaveProxyResult.Ok>(save(created.id.value, "EU", "http://alice:pw@other.example:8080")).proxy
        assertEquals("http://alice:pw@other.example:8080", moved.url.value)
        assertEquals(1, repo.list().size)
        assertEquals(SaveProxyResult.NotFound, save("missing", "x", "http://h:1"))
    }

    @Test
    fun a_node_picks_a_proxy_which_start_resolves_and_which_cannot_be_deleted_while_used() = runTest {
        val proxies = FakeProxyRepository()
        val nodes = FakeNodeRepository()
        val node = Node(
            id = NodeId.parse("n1")!!,
            serverId = ServerId.parse("s1")!!,
            name = "arc testnet",
            network = NetworkId.parse("arc")!!,
            env = EnvId.TESTNET,
            createdAt = "t",
            updatedAt = "t",
        )
        nodes.insert(node)
        val proxy = (SaveProxyUseCase(proxies)(null, "EU", "http://bob:pw@proxy.example:3128") as SaveProxyResult.Ok).proxy
        val setProxy = SetNodeProxyUseCase(nodes, proxies)

        assertEquals(SetNodeProxyResult.ProxyNotFound, setProxy("n1", "nope"))
        assertEquals(SetNodeProxyResult.Saved("n1", proxy.id.value), setProxy("n1", proxy.id.value))
        val withProxy = nodes.findById(node.id)!!
        assertEquals("http://bob:pw@proxy.example:3128", ResolveNodeProxyUrl(proxies)(withProxy))
        assertEquals(listOf("arc testnet"), ListProxiesUseCase(proxies, nodes)().single().nodes)
        assertEquals(DeleteProxyResult.InUse(listOf("arc testnet")), DeleteProxyUseCase(proxies, nodes)(proxy.id.value))

        assertEquals(SetNodeProxyResult.Saved("n1", ""), setProxy("n1", null))
        assertEquals("", ResolveNodeProxyUrl(proxies)(nodes.findById(node.id)!!))
        assertEquals(DeleteProxyResult.Ok, DeleteProxyUseCase(proxies, nodes)(proxy.id.value))
    }

    @Test
    fun a_proxy_that_does_not_answer_is_not_saved_unless_the_check_is_skipped() = runTest {
        val repo = FakeProxyRepository()
        val down = ProxyCheckRunner { _, _ -> ProxyCheck.Failed("connect timed out") }
        val save = SaveProxyUseCase(repo, down)

        assertEquals(SaveProxyResult.Unreachable("connect timed out"), save(null, "EU", "http://h:3128"))
        assertEquals(0, repo.list().size)
        assertIs<SaveProxyResult.Ok>(save(null, "EU", "http://h:3128", skipCheck = true))
        assertEquals(1, repo.list().size)

        val keepOnEdit = repo.list().single()
        // the masked URL sent back must be checked with the stored password, not the literal ***
        var checked = ""
        val spy = ProxyCheckRunner { url, _ -> checked = url.value; ProxyCheck.Ok(5, "t") }
        SaveProxyUseCase(repo, spy)(keepOnEdit.id.value, "EU", keepOnEdit.url.masked)
        assertEquals(keepOnEdit.url.value, checked)
        assertEquals(ProxyCheck.Failed("url is required"), TestProxyUseCase(repo, spy)(null, " "))
    }
}
