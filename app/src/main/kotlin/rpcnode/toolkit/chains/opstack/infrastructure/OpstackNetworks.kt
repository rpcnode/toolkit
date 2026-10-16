package rpcnode.toolkit.chains.opstack.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackSnapshotSource.Alchemy
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackSnapshotSource.Fixed
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackSnapshotSource.Gelato
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackSyncMode.CONSENSUS_LAYER
import rpcnode.toolkit.chains.opstack.infrastructure.OpstackSyncMode.EXECUTION_LAYER

/**
 * Every OP Stack network that upstream op-reth (or the World Chain reth fork) + upstream op-node run from a Superchain Registry
 * name — the only per-network data of the family. Facts: research/opstack (registry `sequencer_rpc` / `block_time` / names read
 * from superchain-registry + op-reth `chain_spec_macro.rs`; peers from each operator's published node repo or API).
 *
 * Deferred (need forks or a decision, not here): celo, mantle, redstone, blast, b3, cyber, fraxtal, hemi.
 */
object OpstackNetworks
{
    private fun env(
        id: String, rethChain: String, nodeNetwork: String, sequencer: String, blockTimeSec: Int, base: Int,
        sync: OpstackSyncMode, snapshot: OpstackSnapshotSource,
        clStatic: List<String> = emptyList(), clBootnodes: List<String> = emptyList(),
        elTrusted: List<String> = emptyList(), elExtraFlags: List<String> = emptyList(),
    ) = OpstackEnvSpec(
        id = id, rethChain = rethChain, nodeNetwork = nodeNetwork, sequencer = sequencer, blockTimeSec = blockTimeSec,
        syncMode = sync, snapshot = snapshot, clStatic = clStatic, clBootnodes = clBootnodes, elTrusted = elTrusted,
        elExtraFlags = elExtraFlags, ports = OpstackPorts.fromBase(base),
    )

    /** Unichain: 1 s blocks; Uniswap's own node repo syncs op-reth with `execution-layer` and op-reth carries the default bootnodes. */
    val UNICHAIN = OpstackNetworkSpec(
        network = NetworkId.UNICHAIN,
        label = "Unichain",
        envs = listOf(
            env("mainnet", "unichain", "unichain-mainnet", "https://mainnet-sequencer.unichain.org", 1, 42620, EXECUTION_LAYER,
                Alchemy(page = "unichain", path = "unichain/main")),
            env("sepolia", "unichain-sepolia", "unichain-sepolia", "https://sepolia-sequencer.unichain.org", 1, 42630, EXECUTION_LAYER,
                Alchemy(page = "unichain-testnet", path = "unichain/sep")),
        ),
    )

    /**
     * Ink: 1 s blocks. The published p2p peers are the ones in the `op-node.env` files of inkonchain/node (envs/ink-mainnet, envs/ink-sepolia) (that repo still runs op-geth,
     * which is end-of-support; the peers serve the same CL gossip).
     */
    val INK = OpstackNetworkSpec(
        network = NetworkId.INK,
        label = "Ink",
        envs = listOf(
            env("mainnet", "ink", "ink-mainnet", "https://rpc-gel.inkonchain.com", 1, 41750, CONSENSUS_LAYER,
                Alchemy(page = "ink", path = "ink/main"),
                clStatic = listOf(
                    "/ip4/34.6.118.237/tcp/9003/p2p/16Uiu2HAmKjbznKezrhsXMXVT3tSwviv8jojmfLeY9Zd53VvLmGsn",
                    "/ip4/34.178.43.233/tcp/9003/p2p/16Uiu2HAm27U7EgvM6C7N2Y3152ysiEd7K3YGXVzZ2ypsnu5ZE4cY",
                    "/ip4/34.6.162.247/tcp/9003/p2p/16Uiu2HAmDhreSpDkx8F78jiJPRV9Npm6VoNB2trgNBNWBreLsBmH",
                ),
                clBootnodes = listOf(
                    "enode://92534205dd72a8787df902c89a775d9a45882739a973a7030d4d338c9b65387cfb77629907f9b6afb4e2bd98d9e6d2ccfde02d8a968b4fbccb68f0f4db5f4d83@34.13.199.202:30305?discport=30305",
                    "enode://26b0306725a78a8d193c2074bfd9e71233dbd3b75ab74be0ab555f72865fd88a285774526243f36653844eb95d62108c35f8c2723656b2c9568716ee2463f8d7@34.7.48.80:30305?discport=30305",
                    "enode://0c7bbb6bca9ce80952d8d8f8ba966e6b9e80faf75a165f320a53722f32b51c4b1da16ba59ab14f5194e51095a2755d8d3086a0220e34d88729cc8f4cc5499116@35.204.27.165:30305?discport=30305",
                    "enode://869d07b5932f17e8490990f75a3f94195e9504ddb6b85f7189e5a9c0a8fff8b00aecf6f3ac450ecba6cdabdb5858788a94bde2b613e0f2d82e9b395355f76d1a@34.65.67.101:30305?discport=30305",
                    "enode://2d4e7e9d48f4dd4efe9342706dd1b0024681bd4c3300d021f86fc75eab7865d4e0cbec6fbc883f011cfd6a57423e7e2f6e104baad2b744c3cafaec6bc7dc92c1@34.65.43.171:30305?discport=30305",
                    "enode://9d7a3efefe442351217e73b3a593bcb8efffb55b4807699972145324eab5e6b382152f8d24f6301baebbfb5ecd4127bd3faab2842c04cd432bdf50ba092f6645@34.65.109.126:30305?discport=30305",
                )),
            env("sepolia", "ink-sepolia", "ink-sepolia", "https://rpc-gel-sepolia.inkonchain.com", 1, 41760, CONSENSUS_LAYER,
                Alchemy(page = "ink-testnet", path = "ink/sep"),
                clStatic = listOf(
                    "/ip4/34.90.221.85/tcp/9003/p2p/16Uiu2HAmMLp2HvyuJt3eWgGRvsxAiT2ThP6CvyyNscjm7xAgL1xH",
                    "/ip4/34.178.231.114/tcp/9003/p2p/16Uiu2HAmL2D9SnbBxd9v5ATrchRp5cbJvAvK4JBwbm86sCs5TB15",
                    "/ip4/34.13.232.240/tcp/9003/p2p/16Uiu2HAm4QD8iwJPt2KCMy2LkkXcvNiBm4RrkUNwNyMNumdKaYL4",
                ),
                clBootnodes = listOf(
                    "enode://410e7729ff4edd1b913efee100a20fed51a2bf0b5b1e0dde4dd66506d0f5cd1dd8a4bef566219d1fe4577174acc656c47186bed389991de629efa8664d0e1150@34.7.113.144:30305?discport=30305",
                    "enode://dbbcefd1b8c57c402cf4db35005b1125a42293457f67965d3c0f8370350f3667c8c9d840f820b37abebc2b17bba3cb46ae80d4306e2020cdac417dcc5ca653bd@34.158.168.67:30305?discport=30305",
                    "enode://42445d7a71c2519492bd355ab08859c9e24b52dcc330a2672561cce944714ecb94c15861a31a8624808011651662d46a5d82f0d932f2fce96c94615b2259020f@34.178.218.241:30305?discport=30305",
                    "enode://869d07b5932f17e8490990f75a3f94195e9504ddb6b85f7189e5a9c0a8fff8b00aecf6f3ac450ecba6cdabdb5858788a94bde2b613e0f2d82e9b395355f76d1a@34.65.67.101:30305?discport=30305",
                    "enode://2d4e7e9d48f4dd4efe9342706dd1b0024681bd4c3300d021f86fc75eab7865d4e0cbec6fbc883f011cfd6a57423e7e2f6e104baad2b744c3cafaec6bc7dc92c1@34.65.43.171:30305?discport=30305",
                )),
        ),
    )

    /** Soneium (`sepolia` = Minato). No operator docs exist; only mainnet has a (third-party) snapshot. */
    val SONEIUM = OpstackNetworkSpec(
        network = NetworkId.SONEIUM,
        label = "Soneium",
        envs = listOf(
            env("mainnet", "soneium", "soneium-mainnet", "https://rpc.soneium.org", 2, 42410, CONSENSUS_LAYER,
                Alchemy(page = "soneium", path = "soneium/main")),
            env("sepolia", "soneium-minato-sepolia", "soneium-minato-sepolia", "https://rpc.minato.soneium.org", 2, 42420, CONSENSUS_LAYER,
                OpstackSnapshotSource.None),
        ),
    )

    /** World Chain: its own reth fork (`world-chain` release tarball) + upstream op-node; official S3 snapshots. */
    val WORLDCHAIN = OpstackNetworkSpec(
        network = NetworkId.WORLDCHAIN,
        label = "World Chain",
        elFlavor = OpstackElFlavor.WORLD_CHAIN,
        envs = listOf(
            env("mainnet", "worldchain", "worldchain-mainnet", "https://worldchain-mainnet-sequencer.g.alchemy.com", 2, 42680, EXECUTION_LAYER,
                Fixed(
                    full = "https://world-chain-snapshots.s3.eu-central-2.amazonaws.com/reth_full.tar.lz4",
                    archive = "https://world-chain-snapshots.s3.eu-central-2.amazonaws.com/reth_archive.tar.lz4",
                ),
                // the official compose enables it; it also needs the sentry peers the fork ships for mainnet (see the docs)
                elExtraFlags = listOf("--flashblocks.enabled")),
            env("sepolia", "worldchain-sepolia", "worldchain-sepolia", "https://worldchain-sepolia-sequencer.g.alchemy.com", 2, 42690, EXECUTION_LAYER,
                Fixed(
                    full = "https://world-chain-testnet-snapshots.s3.eu-central-2.amazonaws.com/reth_full.tar.lz4",
                    archive = "https://world-chain-testnet-snapshots.s3.eu-central-2.amazonaws.com/reth_archive.tar.lz4",
                )),
        ),
    )

    /** Lisk (mainnet only: the Sepolia RPC is dead and its op-node needs a patch). Pins equal LiskHQ/lisk-node. */
    val LISK = OpstackNetworkSpec(
        network = NetworkId.LISK,
        label = "Lisk",
        envs = listOf(
            env("mainnet", "lisk", "lisk-mainnet", "https://rpc.api.lisk.com", 2, 41870, CONSENSUS_LAYER,
                Gelato(host = "lisk.snapshots.gelato.cloud"),
                clStatic = listOf("/ip4/34.12.217.4/tcp/9003/p2p/16Uiu2HAmGozvwRfvZkcET659CPuYS8edK1dWMNGtDYvxcNN6Le4P"),
                clBootnodes = listOf(
                    "enr:-Iu4QBCIUw0OhAq5ErARIYBLIr6xPb83OqeFRqlUJGlJ9kWpNECaumPArYWsEFdM_cEKm26JJT_EA52f4dlavBldldgBgmlkgnY0gmlwhCPqq5CJc2VjcDI1NmsxoQLBE74gZyIHkwBEnkI4K7nR4SM2Lytj2VTLqJaj5nQevoN0Y3CCIyuDdWRwgiMs",
                )),
        ),
    )

    /**
     * Mode (Conduit-hosted): NO public snapshot (Conduit's bucket is requester-pays). Without an operator `snapshot_url` the node
     * derives the chain from L1 — weeks. Mainnet peers are Conduit's public API (`/public/network/{staticPeers,elPeers}/mode-mainnet-0`);
     * Sepolia has none published.
     */
    val MODE = OpstackNetworkSpec(
        network = NetworkId.MODE,
        label = "Mode",
        envs = listOf(
            env("mainnet", "mode", "mode-mainnet", "https://mainnet-sequencer.mode.network", 2, 41990, CONSENSUS_LAYER,
                OpstackSnapshotSource.None,
                clStatic = listOf("/ip4/136.118.47.173/tcp/9222/p2p/16Uiu2HAmMKHvDksC8TCg3GcAQ2QeL79h1W42saqB1qsejZkhtVni"),
                elTrusted = listOf("enode://0bda30d22e70339258f6ced3898373e53cfe50113d60be47b50ba3b7fb0de7e5eb7f4652b2e27718a91d2654da1b0377edc85e862fe5d3bfda9d9496deb987c1@136.118.47.173:30303")),
            env("sepolia", "mode-sepolia", "mode-sepolia", "https://sepolia.mode.network", 2, 42000, CONSENSUS_LAYER,
                OpstackSnapshotSource.None),
        ),
    )

    /** Zora (Conduit-hosted): same constraints as [MODE]. */
    val ZORA = OpstackNetworkSpec(
        network = NetworkId.ZORA,
        label = "Zora",
        envs = listOf(
            env("mainnet", "zora", "zora-mainnet", "https://rpc.zora.energy", 2, 42830, CONSENSUS_LAYER,
                OpstackSnapshotSource.None,
                clStatic = listOf("/ip4/34.105.63.109/tcp/9222/p2p/16Uiu2HAmBVnDhebP319riSPTMkU6gU2kfm7sLTxRok85Gn4TzB7j"),
                elTrusted = listOf("enode://d28b13a0a417e93e85f6ed320787690132ff4301c90b328a8e81de0f25207e33d7a42bbfb3125cf272626492e8dbb5cab2cfcb8d78e9272dfe8a16653d2e99f1@34.105.63.109:30303")),
            env("sepolia", "zora-sepolia", "zora-sepolia", "https://sepolia.rpc.zora.energy", 2, 42840, CONSENSUS_LAYER,
                OpstackSnapshotSource.None),
        ),
    )

    val ALL: List<OpstackNetworkSpec> = listOf(UNICHAIN, INK, SONEIUM, WORLDCHAIN, LISK, MODE, ZORA)

    fun byNetwork(network: NetworkId): OpstackNetworkSpec? = ALL.firstOrNull { it.network == network }
}
