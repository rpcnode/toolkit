package rpcnode.toolkit.chains.cometbft.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.cometbft.infrastructure.CometbftProfile.ArchiveKind
import rpcnode.toolkit.chains.cometbft.infrastructure.http.CometbftSnapshotSource as Src

/** The shipped CometBFT networks. Seeds are the chain-registry / vendor seeds; ports are the PORTS.md blocks. */
object CometbftProfiles
{
    val COSMOS = CometbftProfile(
        network = NetworkId.COSMOS,
        binary = "gaiad",
        envs = listOf(
            CometbftEnv(
                id = "mainnet", chainId = "cosmoshub-4", basePort = 41300, minGasPrice = "0.0025uatom",
                seeds = "ade4d8bc8cbe014af6ebdf3cb7b1e9ad36f412c0@seeds.polkachu.com:14956," +
                    "20e1000e88125698264454a884812746c2eb4807@seeds.lavenderfive.com:14956," +
                    "8542cd7e6bf9d260fef543bc49e59be5a3fa9074@seed.publicnode.com:26656",
            ),
        ),
    )

    val OSMOSIS = CometbftProfile(
        network = NetworkId.OSMOSIS,
        binary = "osmosisd",
        envs = listOf(
            CometbftEnv(
                id = "mainnet", chainId = "osmosis-1", basePort = 42110, minGasPrice = "0uosmo",
                seeds = "ade4d8bc8cbe014af6ebdf3cb7b1e9ad36f412c0@seeds.polkachu.com:12556," +
                    "20e1000e88125698264454a884812746c2eb4807@seeds.lavenderfive.com:12556",
            ),
            CometbftEnv(
                id = "testnet", chainId = "osmo-test-5", basePort = 42120, minGasPrice = "0uosmo",
                seeds = "bb197876fd952d245ef6377e3651c157e3d7ed81@157.245.26.231:26656," +
                    "7c2b9e76be5c2142c76b429d9c29e902599ceb44@157.245.21.183:26656",
            ),
        ),
    )

    val CELESTIA = CometbftProfile(
        network = NetworkId.CELESTIA,
        binary = "celestia-appd",
        archiveGlob = "celestia-app-linux-*.tar.gz",
        archiveKind = ArchiveKind.TAR_GZ,
        // docs.celestia.org: pruned consensus node measured at 206 GB with exactly this retention
        minRetainBlocks = 3000,
        // mandatory since celestia-app v4: the gRPC listener the DA nodes read
        extraStartArgs = listOf("--rpc.grpc_laddr", "tcp://127.0.0.1:{grpc_laddr}"),
        envs = listOf(
            CometbftEnv(
                id = "mainnet", chainId = "celestia", basePort = 41240, minGasPrice = "0.002utia",
                seeds = "e6116822e1a5e283d8a85d3ec38f4d232274eaf3@consensus-full-seed-1.celestia-bootstrap.net:26656," +
                    "ade4d8bc8cbe014af6ebdf3cb7b1e9ad36f412c0@seeds.polkachu.com:11656",
            ),
        ),
    )

    val XRPLEVM = CometbftProfile(
        network = NetworkId.XRPLEVM,
        binary = "exrpd",
        archiveGlob = "exrpd-linux-*.tar.gz",
        archiveKind = ArchiveKind.TAR_GZ,
        evm = EvmKind.COSMOS_EVM,
        // the whole chain is ~0.3 GB: keep all blocks
        minRetainBlocks = 0,
        logName = "xrplevm",
        envs = listOf(
            CometbftEnv(
                id = "mainnet", chainId = "xrplevm_1440000-1", basePort = 42770, minGasPrice = "0axrp", evmChainId = "1440000",
                seeds = "2dc8d776176a275bc64421e31d9b1ed6af228fa5@seed-0.mainnet.xrplevm.org:26656," +
                    "ade4d8bc8cbe014af6ebdf3cb7b1e9ad36f412c0@seeds.polkachu.com:30056",
            ),
            CometbftEnv(
                id = "testnet", chainId = "xrplevm_1449000-1", basePort = 42780, minGasPrice = "0axrp", evmChainId = "1449000",
                seeds = "cb25e37d2ecd3f8b97f8ee66b8fc38b95a087e4e@seed-0.testnet.xrplevm.org:26656," +
                    "ade4d8bc8cbe014af6ebdf3cb7b1e9ad36f412c0@testnet-seeds.polkachu.com:30056",
            ),
        ),
    )

    val SEI = CometbftProfile(
        network = NetworkId.SEI,
        binary = "seid",
        archiveGlob = "sei-chain-linux-*.tar.gz",
        archiveKind = ArchiveKind.TAR_GZ,
        style = KeyStyle.KEBAB,
        evm = EvmKind.SEI,
        // `seid init` writes the genesis and the bootstrap peers itself (v6.6.2+)
        shipsGenesis = false,
        envs = listOf(
            CometbftEnv(id = "mainnet", chainId = "pacific-1", basePort = 42350, minGasPrice = "0.02usei"),
            CometbftEnv(id = "testnet", chainId = "atlantic-2", basePort = 42360, minGasPrice = "0.02usei"),
        ),
    )

    val INJECTIVE = CometbftProfile(
        network = NetworkId.INJECTIVE,
        binary = "injectived",
        archiveGlob = "linux-amd64.zip",
        archiveKind = ArchiveKind.ZIP,
        evm = EvmKind.COSMOS_EVM,
        logName = "injective",
        envs = listOf(
            CometbftEnv(
                id = "mainnet", chainId = "injective-1", basePort = 41720, minGasPrice = "160000000inj", timeoutCommit = "300ms",
                seeds = "d58582c3d49b6cffc465ee12e0d57b3d73f539f7@seed.mainnet.asia.injective.network:26656," +
                    "ade4d8bc8cbe014af6ebdf3cb7b1e9ad36f412c0@seeds.polkachu.com:14356",
            ),
        ),
    )

    val ALL: List<CometbftProfile> = listOf(COSMOS, OSMOSIS, INJECTIVE, SEI, CELESTIA, XRPLEVM)

    /** Snapshot sources per network, `(env, type)` → source. A type that is absent here is not offered. */
    fun snapshotSources(profile: CometbftProfile): Map<Pair<String, String>, Src> = when (profile.network)
    {
        NetworkId.COSMOS -> mapOf(
            ("mainnet" to "pruned") to Src.PolkachuPage("https://polkachu.com/tendermint_snapshots/cosmos", "cosmos"),
        )
        NetworkId.OSMOSIS -> mapOf(
            ("mainnet" to "pruned") to Src.LatestText("https://snapshots.osmosis.zone/latest"),
            ("mainnet" to "polkachu") to Src.PolkachuPage("https://polkachu.com/tendermint_snapshots/osmosis", "osmosis"),
            ("testnet" to "pruned") to Src.LatestText("https://snapshots.testnet.osmosis.zone/latest"),
        )
        NetworkId.INJECTIVE -> mapOf(
            ("mainnet" to "pruned") to Src.StatusJson(
                listOf(
                    "http://injective-mainnet-snapshots.s3-website.gra.io.cloud.ovh.net/status.json",
                    "https://storage.googleapis.com/injective-mainnet-snapshots-asia/status.json",
                ),
            ),
            ("mainnet" to "polkachu") to Src.PolkachuPage("https://polkachu.com/tendermint_snapshots/injective", "injective"),
        )
        NetworkId.SEI -> mapOf(
            ("mainnet" to "pruned") to Src.PolkachuPage("https://polkachu.com/tendermint_snapshots/sei", "sei"),
            ("testnet" to "pruned") to Src.PolkachuPage("https://polkachu.com/testnets/sei/snapshots", "sei", testnet = true),
        )
        NetworkId.CELESTIA -> mapOf(
            ("mainnet" to "pruned") to Src.PolkachuPage("https://polkachu.com/tendermint_snapshots/celestia", "celestia"),
        )
        NetworkId.XRPLEVM -> mapOf(
            ("mainnet" to "pruned") to Src.Fixed("https://xrpl-evm-snapshots.s3.us-east-1.amazonaws.com/mainnet.tar.lz4"),
            ("testnet" to "pruned") to Src.Fixed("https://xrpl-evm-snapshots.s3.us-east-1.amazonaws.com/testnet.tar.lz4"),
        )
        else -> emptyMap()
    }
}
