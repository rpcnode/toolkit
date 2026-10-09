package rpcnode.toolkit.nodes.application.snapshot

import rpcnode.toolkit.networks.domain.repository.NetworkFactsRepository
import rpcnode.toolkit.nodes.domain.model.Node

/**
 * Whether this node's network + env has a snapshot bootstrap step (`chains/<id>/network.yml`,
 * `envs[].snapshot: required`) — the admin uses this to decide if "Snapshot" belongs in the
 * install wizard rail, instead of guessing from the network id on the frontend.
 */
fun nodeNeedsSnapshot(node: Node, facts: NetworkFactsRepository): Boolean
{
    val env = facts.factsFor(node.network)?.envs?.firstOrNull { it.id == node.env.value }
    return env?.snapshot == "required"
}

/** `envs[].snapshotBootstrap: via_node` — chain process fetches the archive; no toolkit CDN URL. */
fun nodeSnapshotViaNode(node: Node, facts: NetworkFactsRepository): Boolean
{
    val env = facts.factsFor(node.network)?.envs?.firstOrNull { it.id == node.env.value }
    return env?.snapshotBootstrap == "via_node"
}
