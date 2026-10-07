package rpcnode.toolkit.nodes.application.config

import kotlin.test.Test
import kotlin.test.assertEquals
import rpcnode.toolkit.clients.domain.model.PortConfigPolicy
import rpcnode.toolkit.clients.domain.model.ProgramPort
import rpcnode.toolkit.networks.domain.model.ClientConfigBindingFacts
import rpcnode.toolkit.networks.domain.model.ClientConfigFacts
import rpcnode.toolkit.networks.domain.model.SnapshotTypeFacts
import rpcnode.toolkit.nodes.domain.model.DiskRolePlacement
import rpcnode.toolkit.nodes.domain.model.NodeDiskLayout

class ResolveClientConfigAssignmentsTest
{
    @Test
    fun lite_snapshot_rewrites_storage_dirs_to_litefullnode()
    {
        val config = ClientConfigFacts(
            program = "FullNode.jar",
            format = "hoocon",
            bindings = listOf(
                ClientConfigBindingFacts(
                    path = "storage.db.directory",
                    source = "disk_role_dir",
                    role = "fullnode",
                    relative = "database",
                ),
                ClientConfigBindingFacts(
                    path = "storage.index.directory",
                    source = "disk_role_dir",
                    role = "fullnode",
                    relative = "index",
                ),
            ),
        )
        val layout = NodeDiskLayout(
            roles = listOf(
                DiskRolePlacement(
                    id = "fullnode",
                    dir = "/mnt/raid0/rpcnode/tron/nile/fullnode",
                    mount = "/mnt/raid0",
                ),
            ),
        )
        val types = listOf(
            SnapshotTypeFacts(id = "lite", kind = "lite", label = "Lite", destLeaf = "litefullnode"),
            SnapshotTypeFacts(id = "full", kind = "full", label = "Full"),
        )
        val out = resolveClientConfigAssignments(
            config = config,
            layout = layout,
            ports = emptyList(),
            installOptionsJson = """{"snapshot":"lite"}""",
            snapshotTypes = types,
        )
        assertEquals("/mnt/raid0/rpcnode/tron/nile/litefullnode/database", out["storage.db.directory"])
        assertEquals("/mnt/raid0/rpcnode/tron/nile/litefullnode/index", out["storage.index.directory"])
    }

    @Test
    fun full_snapshot_keeps_fullnode_leaf()
    {
        val config = ClientConfigFacts(
            program = "FullNode.jar",
            format = "hoocon",
            bindings = listOf(
                ClientConfigBindingFacts(
                    path = "storage.db.directory",
                    source = "disk_role_dir",
                    role = "fullnode",
                    relative = "database",
                ),
            ),
        )
        val layout = NodeDiskLayout(
            roles = listOf(
                DiskRolePlacement(
                    id = "fullnode",
                    dir = "/mnt/raid0/rpcnode/tron/nile/fullnode",
                    mount = "/mnt/raid0",
                ),
            ),
        )
        val out = resolveClientConfigAssignments(
            config = config,
            layout = layout,
            ports = emptyList(),
            installOptionsJson = """{"snapshot":"full"}""",
            snapshotTypes = listOf(SnapshotTypeFacts(id = "full", kind = "full", label = "Full")),
        )
        assertEquals("/mnt/raid0/rpcnode/tron/nile/fullnode/database", out["storage.db.directory"])
    }

    @Test
    fun optional_blocksdir_skipped_on_single_mount_layout()
    {
        val config = ClientConfigFacts(
            program = "bitcoin",
            format = "ini",
            bindings = listOf(
                ClientConfigBindingFacts(
                    path = "datadir",
                    source = "disk_role_dir",
                    role = "blockchain",
                ),
                ClientConfigBindingFacts(
                    path = "blocksdir",
                    source = "disk_role_dir",
                    role = "index",
                    optional = true,
                ),
            ),
        )
        val layout = NodeDiskLayout(
            strategy = "single",
            roles = listOf(
                DiskRolePlacement(
                    id = "blockchain",
                    mount = "/media/storage12tb",
                    dir = "/media/storage12tb/rpcnode/bitcoin/mainnet/blockchain",
                ),
                DiskRolePlacement(
                    id = "index",
                    mount = "/media/storage12tb",
                    dir = "/media/storage12tb/rpcnode/bitcoin/mainnet/index",
                ),
            ),
        )
        val out = resolveClientConfigAssignments(
            config = config,
            layout = layout,
            ports = emptyList(),
            installOptionsJson = "{}",
        )
        assertEquals("/media/storage12tb/rpcnode/bitcoin/mainnet/blockchain", out["datadir"])
        assertEquals(null, out["blocksdir"])
        assertEquals(setOf("blocksdir"), resolveClientConfigOmitIniKeys(config, out))
    }

    @Test
    fun optional_blocksdir_emitted_on_jbod_layout()
    {
        val config = ClientConfigFacts(
            program = "bitcoin",
            format = "ini",
            bindings = listOf(
                ClientConfigBindingFacts(
                    path = "datadir",
                    source = "disk_role_dir",
                    role = "blockchain",
                ),
                ClientConfigBindingFacts(
                    path = "blocksdir",
                    source = "disk_role_dir",
                    role = "index",
                    optional = true,
                ),
            ),
        )
        val layout = NodeDiskLayout(
            strategy = "jbod_2",
            roles = listOf(
                DiskRolePlacement(
                    id = "blockchain",
                    mount = "/mnt/nvme0",
                    dir = "/mnt/nvme0/rpcnode/bitcoin/mainnet/blockchain",
                ),
                DiskRolePlacement(
                    id = "index",
                    mount = "/media/storage12tb",
                    dir = "/media/storage12tb/rpcnode/bitcoin/mainnet/index",
                ),
            ),
        )
        val out = resolveClientConfigAssignments(
            config = config,
            layout = layout,
            ports = emptyList(),
            installOptionsJson = "{}",
        )
        assertEquals("/media/storage12tb/rpcnode/bitcoin/mainnet/index", out["blocksdir"])
        assertEquals(emptySet(), resolveClientConfigOmitIniKeys(config, out))
    }

    @Test
    fun bitcoin_zmq_endpoints_use_catalog_ports_when_enabled()
    {
        val config = ClientConfigFacts(
            program = "bitcoin",
            format = "ini",
            bindings = listOf(
                ClientConfigBindingFacts(
                    path = "zmqpubrawblock",
                    source = "catalog_zmq_bind",
                    role = "zmq_rawblock",
                    optional = true,
                ),
                ClientConfigBindingFacts(
                    path = "zmqpubrawtx",
                    source = "catalog_zmq_bind",
                    role = "zmq_rawtx",
                    optional = true,
                ),
            ),
        )
        val ports = listOf(
            ProgramPort(
                role = "zmq_rawblock",
                port = 28332,
                label = "",
                configPolicy = PortConfigPolicy.OPTIONAL,
            ),
            ProgramPort(
                role = "zmq_rawtx",
                port = 28333,
                label = "",
                configPolicy = PortConfigPolicy.OPTIONAL,
            ),
        )
        val off = resolveClientConfigAssignments(
            config = config,
            layout = null,
            ports = ports,
            installOptionsJson = """{"port_zmq_rawblock":"0","port_zmq_rawtx":"0"}""",
        )
        assertEquals(emptyMap(), off)
        assertEquals(
            setOf("zmqpubrawblock", "zmqpubrawtx"),
            resolveClientConfigOmitIniKeys(config, off, ports),
        )
        val on = resolveClientConfigAssignments(
            config = config,
            layout = null,
            ports = ports,
            installOptionsJson = """{"port_zmq_rawblock":"1","port_zmq_rawtx":"1"}""",
        )
        assertEquals("tcp://127.0.0.1:28332", on["zmqpubrawblock"])
        assertEquals("tcp://127.0.0.1:28333", on["zmqpubrawtx"])
    }
}
