package rpcnode.toolkit.chains.arb.infrastructure

import kotlin.test.Test
import kotlin.test.assertTrue

class ArbUnitBodiesTest
{
    @Test
    fun pruned_unit_includes_init_latest_and_ports()
    {
        val body = ArbUnitBodies.nitro(
            env = "mainnet",
            bin = "/data/rpcnode/arbitrum/mainnet/execution/bin/nitro",
            datadir = "/data/rpcnode/arbitrum/mainnet/execution",
            envFile = "/data/rpcnode/arbitrum/mainnet/execution/.toolkit/nitro.env",
            l1Rpc = "http://127.0.0.1:8545",
            l1Beacon = "http://127.0.0.1:5052",
            cluster = ArbClusters.lookup("mainnet"),
            rpcPort = 8547,
            wsPort = 8548,
            wasmRoots = "/data/x/nitro-legacy/machines,/data/x/target/machines",
            archive = false,
            initUrl = "",
            logFile = "/data/rpcnode/arbitrum/mainnet/execution/logs/node.out",
        )
        assertTrue(body.contains("--chain.id=42161"))
        assertTrue(body.contains("--http.port=8547"))
        assertTrue(body.contains("--ws.port=8548"))
        assertTrue(body.contains("--init.latest=pruned"))
        assertTrue(body.contains("Environment=HOME=/data/rpcnode/arbitrum/mainnet/execution"))
        assertTrue(body.contains("--persistent.chain=/data/rpcnode/arbitrum/mainnet/execution"))
        assertTrue(body.contains("LimitNOFILE=1048576"))
        assertTrue(body.contains("WantedBy=multi-user.target"))
    }

    @Test
    fun archive_unit_includes_pathdb_flags()
    {
        val body = ArbUnitBodies.nitro(
            env = "mainnet",
            bin = "/data/rpcnode/arbitrum/mainnet/execution/bin/nitro",
            datadir = "/data/rpcnode/arbitrum/mainnet/execution",
            envFile = "/data/rpcnode/arbitrum/mainnet/execution/.toolkit/nitro.env",
            l1Rpc = "http://127.0.0.1:8545",
            l1Beacon = "http://127.0.0.1:5052",
            cluster = ArbClusters.lookup("mainnet"),
            rpcPort = 8547,
            wsPort = 8548,
            wasmRoots = "/data/x/nitro-legacy/machines,/data/x/target/machines",
            archive = true,
            initUrl = "https://snapshot.arbitrum.foundation/arb1/archive-path/",
            logFile = "/data/rpcnode/arbitrum/mainnet/execution/logs/node.out",
        )
        assertTrue(body.contains("--init.url=https://snapshot.arbitrum.foundation/arb1/archive-path/"))
        assertTrue(body.contains("--execution.caching.archive"))
        assertTrue(body.contains("--execution.caching.state-scheme=path"))
        assertTrue(!body.contains("--init.latest="))
    }
}
