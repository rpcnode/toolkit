package rpcnode.toolkit.catalog.domain

interface NetworkCatalog
{
    fun find(id: NetworkId): Chain?
    fun all(): List<Chain>
}
