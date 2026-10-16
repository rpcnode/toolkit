package rpcnode.toolkit.chains.nonevm.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.aleo.infrastructure.http.AleoClientReleaseResolver
import rpcnode.toolkit.chains.aleo.infrastructure.http.AleoNetworkTipProbe
import rpcnode.toolkit.chains.aleo.infrastructure.http.AleoNodeHeightProbe
import rpcnode.toolkit.chains.aleo.infrastructure.proc.AleoNodeProcessStarter
import rpcnode.toolkit.chains.aleo.infrastructure.start.AleoNodeStart
import rpcnode.toolkit.chains.fuel.infrastructure.http.FuelClientReleaseResolver
import rpcnode.toolkit.chains.fuel.infrastructure.http.FuelNetworkTipProbe
import rpcnode.toolkit.chains.fuel.infrastructure.http.FuelNodeHeightProbe
import rpcnode.toolkit.chains.fuel.infrastructure.proc.FuelNodeProcessStarter
import rpcnode.toolkit.chains.fuel.infrastructure.start.FuelNodeStart
import rpcnode.toolkit.chains.near.infrastructure.http.NearClientReleaseResolver
import rpcnode.toolkit.chains.near.infrastructure.http.NearNetworkTipProbe
import rpcnode.toolkit.chains.near.infrastructure.http.NearNodeHeightProbe
import rpcnode.toolkit.chains.near.infrastructure.proc.NearNodeProcessStarter
import rpcnode.toolkit.chains.near.infrastructure.start.NearNodeStart
import rpcnode.toolkit.chains.polkadot.infrastructure.PolkadotNode
import rpcnode.toolkit.chains.polkadot.infrastructure.http.PolkadotClientReleaseResolver
import rpcnode.toolkit.chains.stacks.infrastructure.http.StacksClientReleaseResolver
import rpcnode.toolkit.chains.stacks.infrastructure.http.StacksNetworkTipProbe
import rpcnode.toolkit.chains.stacks.infrastructure.http.StacksNodeHeightProbe
import rpcnode.toolkit.chains.stacks.infrastructure.http.StacksSnapshotResolver
import rpcnode.toolkit.chains.stacks.infrastructure.proc.StacksNodeProcessStarter
import rpcnode.toolkit.chains.stacks.infrastructure.start.StacksNodeStart
import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateSpecs
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateNetworkTipProbe
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateNodeHeightProbe
import rpcnode.toolkit.chains.substrate.infrastructure.http.SubstrateSnapshotResolver
import rpcnode.toolkit.chains.substrate.infrastructure.start.SubstrateNodeStart
import rpcnode.toolkit.clients.application.GitHubReleaseClient
import rpcnode.toolkit.clients.application.version.ClientReleaseResolver
import rpcnode.toolkit.networks.application.snapshot.SnapshotResolver
import rpcnode.toolkit.networks.application.tip.NetworkTipProbe
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter

/**
 * Maps the non-EVM networks of this family (polkadot, near, fuel, stacks, aleo) onto their classes. `Toolkit` adds these maps
 * to its own and the host agent adds [hostRuntimes], so each shared wiring file takes one line per map.
 * peaq (no upstream binary) and hedera (no operator route) are DEFERRED and not wired.
 */
object NonEvmWiring
{
    fun clientReleaseResolvers(github: GitHubReleaseClient): Map<NetworkId, ClientReleaseResolver> = mapOf(
        NetworkId.POLKADOT to PolkadotClientReleaseResolver(github),
        NetworkId.NEAR to NearClientReleaseResolver(github),
        NetworkId.FUEL to FuelClientReleaseResolver(github),
        NetworkId.STACKS to StacksClientReleaseResolver(github),
        NetworkId.ALEO to AleoClientReleaseResolver(github),
    )

    fun tipProbes(): Map<NetworkId, NetworkTipProbe> = mapOf(
        NetworkId.POLKADOT to SubstrateNetworkTipProbe(),
        NetworkId.NEAR to NearNetworkTipProbe(),
        NetworkId.FUEL to FuelNetworkTipProbe(),
        NetworkId.STACKS to StacksNetworkTipProbe(),
        NetworkId.ALEO to AleoNetworkTipProbe(),
    )

    fun chainStarts(): Map<NetworkId, ChainNodeStart> = mapOf(
        NetworkId.POLKADOT to SubstrateNodeStart(SubstrateSpecs.POLKADOT),
        NetworkId.NEAR to NearNodeStart(),
        NetworkId.FUEL to FuelNodeStart(),
        NetworkId.STACKS to StacksNodeStart(),
        NetworkId.ALEO to AleoNodeStart(),
    )

    /** polkadot: the Parity snapshot files (`substrate-files://`, agent runner); stacks: the Hiro `.tar.zst`. */
    fun snapshotResolvers(): Map<NetworkId, SnapshotResolver> = mapOf(
        NetworkId.POLKADOT to SubstrateSnapshotResolver(SubstrateSpecs.POLKADOT),
        NetworkId.STACKS to StacksSnapshotResolver(),
    )

    /** network id → (process starter, local height probe) for the host agent's `ChainNodeRuntime` map. */
    fun hostRuntimes(): Map<String, Pair<HostNodeProcessStarter, HostNodeHeightProbe>> = mapOf(
        NetworkId.POLKADOT.value to (PolkadotNode.starter() to SubstrateNodeHeightProbe()),
        NetworkId.NEAR.value to (NearNodeProcessStarter() to NearNodeHeightProbe()),
        NetworkId.FUEL.value to (FuelNodeProcessStarter() to FuelNodeHeightProbe()),
        NetworkId.STACKS.value to (StacksNodeProcessStarter() to StacksNodeHeightProbe()),
        NetworkId.ALEO.value to (AleoNodeProcessStarter() to AleoNodeHeightProbe()),
    )
}
