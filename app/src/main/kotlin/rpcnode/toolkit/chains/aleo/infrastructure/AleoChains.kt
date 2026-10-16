package rpcnode.toolkit.chains.aleo.infrastructure

import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmUnit

/**
 * Aleo mainnet only. Testnet is DEFERRED: `testnet-v*` tags carry no binaries and a mainnet snarkOS cannot follow the
 * testnet (a different snarkVM), so it needs a native source build (planned).
 */
object AleoChains
{
    const val ENV = "mainnet"

    /** `--network`: 0 mainnet, 1 testnet, 2 canary. */
    const val NETWORK_ID = 0
    const val BASE_PORT = 41030
    const val REST_PORT = BASE_PORT
    const val P2P_PORT = BASE_PORT + 2
    const val METRICS_PORT = BASE_PORT + 4

    val PUBLIC_TIP_URLS = listOf("https://api.explorer.provable.com/v1/mainnet/block/height/latest")

    /** `GET /<network>/block/height/latest` on the node (the route also exists under `/v1/` and `/v2/`). */
    const val LOCAL_HEIGHT_PATH = "/mainnet/block/height/latest"
}

/** Literals of `snarkos start` (`network.yml` literal bindings, kept equal by `AleoCatalogTest`). */
object AleoSettings
{
    /**
     * Vendor default 10 requests/s per source IP (burst 10, `rest_rps` in snarkOS `start.rs`): behind the toolkit proxy every
     * client shares one IP, so the default would throttle the whole endpoint.
     */
    const val REST_RPS = 1000
    const val VERBOSITY = 1
    const val NOFILE = 1_048_576
    const val RELATIVE_LOG = "logs/snarkos.log"
    const val RELATIVE_STDOUT = "logs/snarkos.stdout"
}

object AleoUnitBodies
{
    /**
     * Client node without an account (snarkOS draws an ephemeral one), CDN prefetch on (default), JWT on (never `--nojwt`:
     * it guards `/db_backup`, `/node/address` and the program mapping routes), metrics on loopback.
     */
    fun flags(ledger: String, nodeData: String, logFile: String): List<String> = listOf(
        "--network=${AleoChains.NETWORK_ID}",
        "--client",
        "--nodisplay",
        "--node=0.0.0.0:${AleoChains.P2P_PORT}",
        "--rest=0.0.0.0:${AleoChains.REST_PORT}",
        "--rest-rps=${AleoSettings.REST_RPS}",
        "--metrics",
        "--metrics-ip=127.0.0.1:${AleoChains.METRICS_PORT}",
        "--ledger-storage=$ledger",
        "--node-data-storage=$nodeData",
        "--logfile=$logFile",
        "--verbosity=${AleoSettings.VERBOSITY}",
    )

    fun unit(nodeDir: String, binary: String, ledger: String, nodeData: String, logFile: String, stdout: String): String =
        NonEvmUnit.render(
            network = "aleo",
            description = "Aleo (mainnet) — RpcNode",
            nodeDir = nodeDir,
            // `start` is the sub-command, its flags follow
            execStart = NonEvmUnit.execStart("$binary start", flags(ledger, nodeData, logFile)),
            logFile = stdout,
            nofile = AleoSettings.NOFILE,
        )
}
