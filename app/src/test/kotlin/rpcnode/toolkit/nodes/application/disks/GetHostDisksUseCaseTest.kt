package rpcnode.toolkit.nodes.application.disks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.nodes.domain.model.HostBlockDevice
import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog
import rpcnode.toolkit.nodes.domain.model.HostMount
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class GetHostDisksUseCaseTest
{
    private val server = Server(
        id = ServerId.parse("srv-1")!!,
        name = "box",
        agentUrl = "http://127.0.0.1:38990",
        agentKey = "tok",
        createdAt = "t",
        updatedAt = "t",
    )

    private val catalog = HostDiskCatalog(
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

    @Test
    fun returns_host_inventory_only() = runTest {
        val uc = GetHostDisksUseCase(
            servers = FakeServerRepository(listOf(server)),
            reader = HostDiskReader { _, _ -> catalog },
        )
        val result = uc("srv-1")
        assertTrue(result is HostDisksResult.Ok)
        val ok = result as HostDisksResult.Ok
        assertEquals(2, ok.catalog.disks.size)
        assertTrue(ok.summary.contains("NVMe"))
    }

    @Test
    fun agent_unreachable_when_server_has_no_token() = runTest {
        val bare = server.copy(agentKey = "")
        val uc = GetHostDisksUseCase(
            servers = FakeServerRepository(listOf(bare)),
            reader = HostDiskReader { _, _ -> catalog },
        )
        assertEquals(HostDisksResult.AgentUnreachable, uc("srv-1"))
    }
}
