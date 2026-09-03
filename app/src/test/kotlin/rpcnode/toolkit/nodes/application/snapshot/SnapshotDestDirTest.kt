package rpcnode.toolkit.nodes.application.snapshot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.SnapshotTypeFacts
import rpcnode.toolkit.networks.infrastructure.facts.YamlNetworkFactsRepository
import rpcnode.toolkit.nodes.application.disks.GetHostDisksUseCase
import rpcnode.toolkit.nodes.application.disks.GetNodeDiskLayoutUseCase
import rpcnode.toolkit.nodes.application.disks.HostDiskReader
import rpcnode.toolkit.nodes.domain.model.HostBlockDevice
import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog
import rpcnode.toolkit.nodes.domain.model.HostMount
import rpcnode.toolkit.nodes.domain.model.Node
import rpcnode.toolkit.nodes.domain.model.NodeId
import rpcnode.toolkit.nodes.FakeNodeRepository
import rpcnode.toolkit.servers.FakeServerRepository
import rpcnode.toolkit.servers.domain.model.Server
import rpcnode.toolkit.servers.domain.model.ServerId

class SnapshotDestDirTest
{
    @Test
    fun resolves_admin_roles_map_and_ledger_dir()
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
        assertEquals("/mnt/raid0/rpcnode/tron/nile/fullnode", snapshotDestDir(raw))
    }

    @Test
    fun raw_fallback_reads_roles_map_when_decode_fails()
    {
        val raw = """
            {
              "roles": {
                "fullnode": {
                  "dir": "/mnt/raid0/rpcnode/tron/nile/fullnode"
                }
              }
            }
        """.trimIndent()
        assertEquals("/mnt/raid0/rpcnode/tron/nile/fullnode", snapshotDestDir(raw))
    }

    @Test
    fun blank_layout_has_no_dest()
    {
        assertNull(snapshotDestDir(""))
        assertNull(snapshotDestDir("{}"))
    }
}

class ResolveSnapshotDestDirUseCaseTest
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
            HostMount(target = "/mnt/raid0", availBytes = 1_500_000_000_000, tran = "nvme", preferred = true),
            HostMount(target = "/mnt/raid1", availBytes = 1_500_000_000_000, tran = "nvme", preferred = true),
        ),
        unused = emptyList(),
    )

    private val node = Node(
        id = NodeId.parse("44444444-4444-4444-8444-444444444444")!!,
        serverId = server.id,
        name = "TRON nile",
        network = NetworkId.TRON,
        env = EnvId.NILE,
        createdAt = "t",
        updatedAt = "t",
    )

    @Test
    fun falls_back_to_enriched_disk_layout_when_saved_json_empty() = runTest {
        val getNodeDiskLayout = GetNodeDiskLayoutUseCase(
            nodes = FakeNodeRepository(listOf(node)),
            facts = YamlNetworkFactsRepository(),
            hostDisks = GetHostDisksUseCase(
                servers = FakeServerRepository(listOf(server)),
                reader = HostDiskReader { _, _ -> catalog },
            ),
        )
        val uc = ResolveSnapshotDestDirUseCase(getNodeDiskLayout, YamlNetworkFactsRepository())
        val dest = uc(node)
        assertEquals("/mnt/raid0/rpcnode/tron/nile/fullnode", dest)
    }

    @Test
    fun resolves_saved_mount_only_layout_via_enrichment() = runTest {
        val saved = """
            {
              "strategy": "jbod_2",
              "roles": {
                "fullnode": { "mount": "/mnt/raid0" },
                "solidity": { "mount": "/mnt/raid1" }
              }
            }
        """.trimIndent()
        val getNodeDiskLayout = GetNodeDiskLayoutUseCase(
            nodes = FakeNodeRepository(listOf(node.copy(diskLayoutJson = saved))),
            facts = YamlNetworkFactsRepository(),
            hostDisks = GetHostDisksUseCase(
                servers = FakeServerRepository(listOf(server)),
                reader = HostDiskReader { _, _ -> null },
            ),
        )
        val uc = ResolveSnapshotDestDirUseCase(getNodeDiskLayout, YamlNetworkFactsRepository())
        val dest = uc(node.copy(diskLayoutJson = saved))
        assertEquals("/mnt/raid0/rpcnode/tron/nile/fullnode", dest)
    }

    @Test
    fun lite_snapshot_type_rewrites_fullnode_leaf_to_litefullnode() = runTest {
        val saved = """
            {
              "roles": {
                "fullnode": {
                  "dir": "/mnt/raid0/rpcnode/tron/nile/fullnode",
                  "mount": "/mnt/raid0"
                }
              }
            }
        """.trimIndent()
        val withLite = node.copy(
            diskLayoutJson = saved,
            installOptionsJson = """{"snapshot":"lite"}""",
        )
        val getNodeDiskLayout = GetNodeDiskLayoutUseCase(
            nodes = FakeNodeRepository(listOf(withLite)),
            facts = YamlNetworkFactsRepository(),
            hostDisks = GetHostDisksUseCase(
                servers = FakeServerRepository(listOf(server)),
                reader = HostDiskReader { _, _ -> null },
            ),
        )
        val uc = ResolveSnapshotDestDirUseCase(getNodeDiskLayout, YamlNetworkFactsRepository())
        assertEquals("/mnt/raid0/rpcnode/tron/nile/litefullnode", uc(withLite))
    }
}

class ApplySnapshotDestLeafTest
{
    @Test
    fun lite_kind_uses_litefullnode_leaf()
    {
        val types = listOf(
            SnapshotTypeFacts(id = "lite", kind = "lite", label = "Lite", destLeaf = "litefullnode"),
            SnapshotTypeFacts(id = "full", kind = "full", label = "Full"),
        )
        assertEquals(
            "/mnt/raid0/rpcnode/tron/nile/litefullnode",
            applySnapshotDestLeaf("/mnt/raid0/rpcnode/tron/nile/fullnode", "lite", types),
        )
        assertEquals(
            "/mnt/raid0/rpcnode/tron/nile/fullnode",
            applySnapshotDestLeaf("/mnt/raid0/rpcnode/tron/nile/fullnode", "full", types),
        )
    }
}
