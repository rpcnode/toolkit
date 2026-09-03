package rpcnode.toolkit.chains.tron.infrastructure.proc

import java.nio.file.Path
import rpcnode.toolkit.nodes.application.start.HostNodeProcessStarter
import rpcnode.toolkit.nodes.application.start.HostNodeStartResult
import rpcnode.toolkit.nodes.application.start.NodeLaunchSpec
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport

/** Host start for java-tron — executes the [TronNodeStart] launch plan on the node dir. */
class TronNodeProcessStarter : HostNodeProcessStarter
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
