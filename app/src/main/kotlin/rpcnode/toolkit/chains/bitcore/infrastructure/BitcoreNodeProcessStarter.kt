package rpcnode.toolkit.chains.bitcore.infrastructure

import java.nio.file.Path
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/** Shared host start for bitcoind-style daemons — extract tarball, systemd unit, restart. */
class BitcoreNodeProcessStarter : HostNodeProcessStarter
{
    override fun start(
        nodeId: String,
        network: String,
        env: String,
        nodeDir: String,
        launch: NodeLaunchSpec,
    ): HostNodeStartResult
    {
        return HostNodeLaunchSupport.startProcess(
            nodeId = nodeId,
            network = network,
            env = env,
            nodeDir = Path.of(nodeDir.trim()),
            launch = launch,
        )
    }
}
