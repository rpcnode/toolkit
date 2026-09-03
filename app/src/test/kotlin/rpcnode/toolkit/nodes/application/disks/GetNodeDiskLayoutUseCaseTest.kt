package rpcnode.toolkit.nodes.application.disks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.DiskRoleDef
import rpcnode.toolkit.nodes.domain.model.NodeDiskLayout
import rpcnode.toolkit.nodes.domain.model.DiskRolePlacement
import rpcnode.toolkit.nodes.domain.model.HostBlockDevice
import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog
import rpcnode.toolkit.nodes.domain.model.HostMount
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class GetNodeDiskLayoutUseCaseTest
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
        disks = listOf(HostBlockDevice(name = "nvme0n1", tran = "nvme", preferred = true)),
        mounts = listOf(
            HostMount(target = "/mnt/data1", availBytes = 1_500_000_000_000, tran = "nvme", preferred = true),
            HostMount(target = "/mnt/data2", availBytes = 1_500_000_000_000, tran = "nvme", preferred = true),
        ),
        unused = emptyList(),
    )

    private val node = Node(
        id = NodeId.parse("44444444-4444-4444-8444-444444444444")!!,
        serverId = server.id,
        name = "TRON mainnet",
        network = NetworkId.TRON,
        env = EnvId.MAINNET,
        createdAt = "t",
        updatedAt = "t",
    )

    @Test
    fun returns_tron_roles_and_recommended_when_nothing_saved() = runTest {
        val uc = GetNodeDiskLayoutUseCase(
            nodes = FakeNodeRepository(listOf(node)),
            facts = YamlNetworkFactsRepository(),
            hostDisks = GetHostDisksUseCase(
                servers = FakeServerRepository(listOf(server)),
                reader = HostDiskReader { _, _ -> catalog },
            ),
        )
        val result = uc(node.id.value)
        assertTrue(result is NodeDiskLayoutResult.Ok)
        val ok = result as NodeDiskLayoutResult.Ok
        assertEquals(2, ok.multiDiskRoles.size)
        assertEquals("fullnode", ok.multiDiskRoles[0].id)
        assertEquals("solidity", ok.multiDiskRoles[1].id)
        assertTrue(ok.layoutRules.isNotEmpty())
        assertNotNull(ok.recommended)
        assertEquals("jbod_2", ok.recommended?.strategy)
        assertNotNull(ok.diskLayout)
        assertEquals(2, ok.diskLayout?.roles?.size)
    }

    @Test
    fun returns_polygon_bor_and_heimdall_roles() = runTest {
        val polygon = node.copy(
            id = NodeId.parse("55555555-5555-5555-8555-555555555555")!!,
            name = "Polygon mainnet",
            network = NetworkId.POLYGON,
        )
        val uc = GetNodeDiskLayoutUseCase(
            nodes = FakeNodeRepository(listOf(polygon)),
            facts = YamlNetworkFactsRepository(),
            hostDisks = GetHostDisksUseCase(
                servers = FakeServerRepository(listOf(server)),
                reader = HostDiskReader { _, _ -> catalog },
            ),
        )
        val ok = uc(polygon.id.value) as NodeDiskLayoutResult.Ok
        assertEquals(2, ok.multiDiskRoles.size)
        assertEquals("bor", ok.multiDiskRoles[0].id)
        assertEquals("heimdall", ok.multiDiskRoles[1].id)
        assertEquals("Bor DB", ok.multiDiskRoles[0].label)
        assertNotNull(ok.recommended)
        assertEquals(2, ok.recommended?.roles?.size)
    }

    @Test
    fun merges_saved_layout_with_catalog_labels() = runTest {
        val saved = """{"strategy":"single","network":"tron","env":"mainnet","roles":[
            {"id":"fullnode","mount":"/mnt/data1","dir":"/mnt/data1/tron/mainnet/fullnode"},
            {"id":"solidity","mount":"/mnt/data1","dir":"/mnt/data1/tron/mainnet/solidity"}
        ]}"""
        val uc = GetNodeDiskLayoutUseCase(
            nodes = FakeNodeRepository(listOf(node.copy(diskLayoutJson = saved))),
            facts = YamlNetworkFactsRepository(),
            hostDisks = GetHostDisksUseCase(
                servers = FakeServerRepository(listOf(server)),
                reader = HostDiskReader { _, _ -> null },
            ),
        )
        val ok = uc(node.id.value) as NodeDiskLayoutResult.Ok
        val roles = ok.diskLayout?.roles.orEmpty()
        assertEquals("FullNode DB", roles.first { it.id == "fullnode" }.label)
        assertEquals("/mnt/data1/tron/mainnet/fullnode", roles.first { it.id == "fullnode" }.dir)
    }
}

class NodeDiskLayoutCodecTest
{
    @Test
    fun enrich_fills_dir_from_mount_and_catalog_leaf() {
        val catalog = listOf(
            DiskRoleDef(id = "blockchain", label = "Blockchain data", leaf = "blockchain"),
            DiskRoleDef(id = "index", label = "Index / auxiliary", leaf = "index"),
        )
        val saved = enrichDiskLayout(
            NodeDiskLayout(
                strategy = "jbod_2",
                network = "bitcoin",
                env = "mainnet",
                roles = listOf(
                    DiskRolePlacement(id = "blockchain", mount = "/mnt/nvme0"),
                    DiskRolePlacement(id = "index", mount = "/mnt/nvme1"),
                ),
            ),
            catalog,
            "bitcoin",
            "mainnet",
        )
        assertNotNull(saved)
        assertTrue(saved.roles.first { it.id == "blockchain" }.dir.contains("/bitcoin/mainnet/blockchain"))
        assertEquals("Blockchain data", saved.roles.first { it.id == "blockchain" }.label)
    }

    @Test
    fun decode_admin_roles_map_with_compat_dirs()
    {
        val raw = """
            {
              "strategy": "jbod_2",
              "ledger_dir": "/mnt/raid0/rpcnode/tron/nile/fullnode",
              "roles": {
                "fullnode": {
                  "dir": "/mnt/raid0/rpcnode/tron/nile/fullnode",
                  "mount": "/mnt/raid0"
                }
              }
            }
        """.trimIndent()
        val layout = assertNotNull(decodeNodeDiskLayout(raw))
        assertEquals("/mnt/raid0/rpcnode/tron/nile/fullnode", layout.ledgerDir)
        assertEquals("/mnt/raid0/rpcnode/tron/nile/fullnode", layout.roles.first { it.id == "fullnode" }.dir)
    }
}
