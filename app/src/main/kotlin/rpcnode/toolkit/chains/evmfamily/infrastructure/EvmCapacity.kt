package rpcnode.toolkit.chains.evmfamily.infrastructure

/**
 * Capacity / exposure literals applied to the units. Every value here also appears as a `literal` binding in the
 * network's `network.yml` (`EvmCatalogTest` compares them), so what the panel shows is what runs.
 */
object EvmCapacity
{
    const val NOFILE = 1_048_576

    // Nethermind (JsonRpc / Network sections)
    const val NETHERMIND_MODULES = "Eth,Net,Web3,Subscribe,Rpc"
    const val NETHERMIND_MAX_BATCH_SIZE = 1000
    const val NETHERMIND_REQUEST_QUEUE_LIMIT = 500
    const val NETHERMIND_MAX_ACTIVE_PEERS = 50

    // reth family
    const val RETH_MAX_CONNECTIONS = 3000
    const val RETH_MAX_RESPONSE_MB = 64
    const val RETH_MAX_BLOCKS_PER_FILTER = 50_000
}
