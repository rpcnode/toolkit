package rpcnode.toolkit.chains.cometbft.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId

/** `snake_case` keys (CometBFT 0.38 / 1.x) or `kebab-case` (Sei's tendermint fork: `max-open-connections`, `[tx-index]`). */
enum class KeyStyle { SNAKE, KEBAB }

/** Which EVM layer the app exposes, if any: cosmos/evm `[json-rpc]` (XRPL EVM, Injective) or Sei's `[evm]`. */
enum class EvmKind { NONE, COSMOS_EVM, SEI }

/** One env of a CometBFT chain: its chain id, seeds, gas price and the first port of its block. */
data class CometbftEnv(
    val id: String,
    val chainId: String,
    val basePort: Int,
    val seeds: String = "",
    val minGasPrice: String,
    /** `evm-chain-id` (XRPL EVM v10+ refuses to start without it). */
    val evmChainId: String? = null,
    /** `consensus.timeout_commit` override (Injective docs: 300ms). */
    val timeoutCommit: String? = null,
)

/**
 * Everything that differs per CometBFT network. The shared classes in this package never branch on the network id:
 * they read a profile.
 */
data class CometbftProfile(
    val network: NetworkId,
    /** Executable name; lives in `<node_dir>/bin/` (raw release binaries are moved there). */
    val binary: String,
    /** Glob of the release archive in the node dir, or null for a raw binary named [binary]. */
    val archiveGlob: String? = null,
    /** Release archive extracted for [binary] and any `*.so` beside it. */
    val archiveKind: ArchiveKind = ArchiveKind.NONE,
    val style: KeyStyle = KeyStyle.SNAKE,
    val evm: EvmKind = EvmKind.NONE,
    val minRetainBlocks: Int = 100_000,
    val envs: List<CometbftEnv>,
    /** Mandatory extra start flags; `{grpc_laddr}` is replaced with the catalog port. */
    val extraStartArgs: List<String> = emptyList(),
    /** True when `genesis.json` is shipped as a clients.yml config file (otherwise `init` writes it, as `seid` does). */
    val shipsGenesis: Boolean = true,
    val logName: String = network.value,
)
{
    enum class ArchiveKind { NONE, TAR_GZ, ZIP }

    fun env(raw: String): CometbftEnv =
        envs.firstOrNull { it.id == raw.trim().lowercase() } ?: envs.first()

    val hasGrpcLaddr: Boolean get() = extraStartArgs.any { it.contains("{grpc_laddr}") }

    /** Catalog ports of [env]: pure CometBFT chains lead with the RPC port, EVM chains with the EVM JSON-RPC port. */
    fun ports(env: CometbftEnv): CometbftPorts
    {
        val b = env.basePort
        return if (evm == EvmKind.NONE)
        {
            CometbftPorts(rpc = b, p2p = b + 2, metrics = b + 4, api = b + 5, grpc = b + 7, grpcLaddr = if (hasGrpcLaddr) b + 8 else null)
        }
        else
        {
            CometbftPorts(rpc = b + 5, p2p = b + 2, metrics = b + 4, api = b + 8, grpc = b + 7, evmHttp = b, evmWs = b + 1)
        }
    }

    /** `HostNodeHeightProbe` port role: the CometBFT RPC. */
    val rpcRole: String get() = if (evm == EvmKind.NONE) "http" else "cl_rpc"
}

data class CometbftPorts(
    val rpc: Int,
    val p2p: Int,
    val metrics: Int,
    val api: Int,
    val grpc: Int,
    val evmHttp: Int? = null,
    val evmWs: Int? = null,
    val grpcLaddr: Int? = null,
)
