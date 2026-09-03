package rpcnode.toolkit.nodes.application.disks

import rpcnode.toolkit.datadir.domain.joinData
import rpcnode.toolkit.nodes.domain.model.DiskRoleDef
import rpcnode.toolkit.nodes.domain.model.DiskRolePlacement
import rpcnode.toolkit.nodes.domain.model.HostDiskCatalog
import rpcnode.toolkit.nodes.domain.model.HostMount
import rpcnode.toolkit.nodes.domain.model.NodeDiskLayout

internal fun recommendDiskLayout(
    catalog: HostDiskCatalog,
    roles: List<DiskRoleDef>,
    network: String,
    env: String,
): NodeDiskLayout?
{
    if (roles.isEmpty())
    {
        return null
    }
    val options = dataMountOptions(catalog)
    if (options.isEmpty())
    {
        return null
    }
    val placements = roles.mapIndexed { index, role ->
        val mount = options[minOf(index, options.lastIndex)]
        DiskRolePlacement(
            id = role.id,
            label = role.label,
            leaf = role.leaf,
            mount = mount,
            dir = pathOnDataMount(mount, network, env, role.leaf),
            sizeHintGiB = role.sizeHintGiB,
        )
    }
    val distinct = placements.map { it.mount }.filter { it.isNotBlank() }.toSet()
    val strategy = when
    {
        distinct.size >= 3 -> "jbod_3"
        distinct.size == 2 -> "jbod_2"
        else -> "single"
    }
    return NodeDiskLayout(
        strategy = strategy,
        network = network,
        env = env,
        roles = placements,
    ).withCompatFields()
}

internal fun dataMountOptions(catalog: HostDiskCatalog): List<String>
{
    val fromMounts = catalog.mounts
        .filter { m ->
            val t = m.target
            t.isNotBlank() && t != "/" && !t.startsWith("/boot") && !t.startsWith("/snap")
        }
        .sortedWith(
            compareByDescending<HostMount> { it.preferred }
                .thenByDescending { it.availBytes },
        )
        .map { it.target }
    val fromUnused = catalog.unused.mapNotNull { d ->
        d.plannedMount.takeIf { it.isNotBlank() && it != "/" && it != "/data" }
    }
    return (fromMounts + fromUnused).distinct().filter { it != "/" && it != "/data" }
}

internal fun envDataLeaf(network: String, env: String): String
{
    val n = network.lowercase()
    val e = env.lowercase()
    if (n == "ltc" && e == "testnet")
    {
        return "testnet4"
    }
    if (n == "avalanche" && e == "testnet")
    {
        return "fuji"
    }
    return e
}

/** Host dir under `/data/rpcnode/` — usually the network id; arb → arbitrum. */
internal fun networkDataRoot(network: String): String
{
    val n = network.trim().lowercase()
    return when (n)
    {
        "arb" -> "arbitrum"
        else -> n
    }
}

internal fun pathOnDataMount(mount: String, network: String, env: String, leaf: String): String
{
    val root = networkDataRoot(network)
    val e = envDataLeaf(network, env)
    return if (leaf.isBlank())
    {
        joinData(mount, root, e)
    }
    else
    {
        joinData(mount, root, e, leaf)
    }
}

internal fun NodeDiskLayout.withCompatFields(): NodeDiskLayout
{
    var next = this
    for (role in roles)
    {
        next = when (role.id)
        {
            "ledger" -> next.copy(ledgerMount = role.mount, ledgerDir = role.dir)
            "accounts" -> next.copy(accountsMount = role.mount, accountsDir = role.dir)
            "snapshots" -> next.copy(snapshotsMount = role.mount, snapshotsDir = role.dir)
            "state" -> next.copy(stateMount = role.mount, stateDir = role.dir)
            "index" -> next.copy(indexMount = role.mount, indexDir = role.dir)
            "fullnode", "blockchain", "chain", "chaindata", "execution" ->
                next.copy(ledgerMount = role.mount, ledgerDir = role.dir)
            "solidity", "consensus", "archive" ->
                next.copy(accountsMount = role.mount, accountsDir = role.dir)
            else -> next
        }
    }
    return next
}
