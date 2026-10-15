package rpcnode.toolkit.chains.ethereum.infrastructure

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EthereumUnitBodiesTest
{
    @Test
    fun geth_unit_contains_snap_and_ports()
    {
        val body = EthereumUnitBodies.geth(
            env = "mainnet",
            bin = "/opt/ethereum/bin/geth",
            datadir = "/data/rpcnode/ethereum/mainnet/geth",
            jwtPath = "/etc/ethereum/mainnet/jwt.hex",
            rpcPort = 8545,
            wsPort = 8554,
            p2pPort = 30303,
            enginePort = 8551,
            cluster = EthereumClusters.lookup("mainnet"),
            archive = false,
            logFile = "/data/rpcnode/ethereum/mainnet/geth/logs/node.out",
        )
        assertTrue(body.contains("--syncmode snap"))
        assertTrue(body.contains("--gcmode full"))
        assertTrue(body.contains("--http.port 8545"))
        assertTrue(body.contains("--ws --ws.addr 0.0.0.0 --ws.port 8554"))
        assertTrue(body.contains("--ws.api eth,net,web3,txpool"))
        assertTrue(body.contains("--history.chain postmerge"))
        assertTrue(body.contains("WantedBy=multi-user.target"))
        assertTrue(!body.contains("User=nodeop"))
    }

    private fun geth(flags: String) = EthereumUnitBodies.geth(
        env = "sepolia",
        bin = "/opt/ethereum/bin/geth",
        datadir = "/data/geth",
        jwtPath = "/etc/ethereum/sepolia/jwt.hex",
        rpcPort = 8546,
        wsPort = 8555,
        p2pPort = 30304,
        enginePort = 8552,
        cluster = EthereumClusters.lookup("sepolia"),
        archive = false,
        logFile = "/data/geth/logs/node.out",
        gethFlags = flags,
    )

    private fun assertOneExecStart(body: String)
    {
        // every continuation line must still end with a backslash, the unit is one ExecStart
        val exec = body.lines().dropWhile { !it.startsWith("ExecStart=") }.takeWhile { !it.startsWith("Restart=") }
        assertTrue(exec.dropLast(1).all { it.trimEnd().endsWith("\\") }, exec.joinToString("\n"))
    }

    @Test
    fun without_overrides_the_unit_keeps_its_defaults()
    {
        val body = geth("")
        assertTrue(body.contains("--cache 2048"))
        assertTrue(body.contains("--maxpeers 100"))
        assertTrue(body.contains("--rpc.batch-request-limit 2000"))
        assertTrue(body.contains("--http.api eth,net,web3,txpool"))
        assertTrue(body.contains("--http.vhosts *"))
        assertOneExecStart(body)
    }

    @Test
    fun overrides_replace_defaults_and_new_keys_are_added()
    {
        val body = geth("cache=8192\n--cache.blocklogs=128\nrpc.batch-response-max-size 100000000\n# note\nhttp.api=eth,net,web3")
        assertTrue(body.contains("--cache 8192") && !body.contains("--cache 2048"))
        assertTrue(body.contains("--cache.blocklogs 128"))
        assertTrue(body.contains("--rpc.batch-response-max-size 100000000"))
        assertTrue(body.contains("--http.api eth,net,web3\n") || body.contains("--http.api eth,net,web3 \\"))
        assertOneExecStart(body)
    }

    @Test
    fun locked_and_unsafe_flags_are_rejected()
    {
        val parsed = EthereumGethFlags.parse("datadir=/x\nauthrpc.port=1\ncache=1;rm -rf /\nhttp.api=$(id)\nBad Key=1\nmaxpeers=50")
        assertEquals(mapOf("maxpeers" to "50"), parsed.overrides)
        assertEquals(5, parsed.rejected.size)
        val body = geth("datadir=/x\nmaxpeers=50")
        assertTrue(!body.contains("--datadir /x") && body.contains("--maxpeers 50"))
    }

    @Test
    fun flags_survive_the_launch_arg_encoding()
    {
        val text = "cache=4096\ncache.blocklogs=128"
        assertEquals(text, EthereumGethFlags.decode(EthereumGethFlags.encode(text)))
        assertEquals("", EthereumGethFlags.decode("%%%"))
        assertEquals(text, ethereumGethFlagsText("""{"geth_flags":"cache=4096\ncache.blocklogs=128"}"""))
    }

    @Test
    fun lighthouse_unit_after_geth()
    {
        val body = EthereumUnitBodies.lighthouse(
            env = "sepolia",
            bin = "/usr/local/bin/lighthouse",
            datadir = "/data/rpcnode/ethereum/sepolia/lighthouse",
            jwtPath = "/etc/ethereum/sepolia/jwt.hex",
            enginePort = 8552,
            beaconPort = 5053,
            consensusP2p = 9100,
            cluster = EthereumClusters.lookup("sepolia"),
            gethUnit = "rpcnode-ethereum-sepolia.service",
            logFile = "/tmp/lh.out",
        )
        assertTrue(body.contains("After=network-online.target rpcnode-ethereum-sepolia.service"))
        assertTrue(body.contains("--network sepolia"))
        assertEquals("rpcnode-ethereum-lighthouse-sepolia.service", EthereumUnitBodies.lighthouseUnitName("sepolia"))
    }
}
