package rpcnode.toolkit.chains.bitcoin.infrastructure.http

import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreChainSpecs
import rpcnode.toolkit.chains.bitcore.infrastructure.BitcoreNodeHeightProbe
import rpcnode.toolkit.nodes.application.start.HostNodeHeightProbe

/** Bitcoin Core height — delegates to [BitcoreNodeHeightProbe]. */
class BitcoinNodeHeightProbe : HostNodeHeightProbe
{
    private val inner = BitcoreNodeHeightProbe(BitcoreChainSpecs.BITCOIN)

    override suspend fun height(nodeDir: String, httpPort: Int, configFile: String, env: String): Long? =
        inner.height(nodeDir, httpPort, configFile, env)
}
