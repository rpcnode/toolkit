package rpcnode.toolkit.chains.polkadot.infrastructure

import rpcnode.toolkit.chains.substrate.infrastructure.SubstrateEnv
import rpcnode.toolkit.chains.substrate.infrastructure.SubstratePorts
import rpcnode.toolkit.nodes.infrastructure.host.HostNodeLaunchSupport
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * The optional REST API companion of a Polkadot node (`install_options.indexer = rest-api`): `polkadot-rest-api`, Parity's
 * successor of substrate-api-sidecar, built from source on the host and run as `rpcnode-polkadot-<env>-indexer.service`.
 * Stateless — no database — so it needs no PostgreSQL and keeps no history; the PostgreSQL-backed one is [PolkadotHistory]
 * (`indexer=history`). See `docs/networks/polkadot-intake.md`.
 *
 * The `SAS_*` values below are also `literal` bindings of `chains/polkadot/network.yml` (kept equal by `PolkadotCatalogTest`).
 */
object PolkadotIndexer
{
    const val OPTION = "indexer"
    const val NONE = "none"
    const val REST_API = "rest-api"

    /** paritytech/polkadot-rest-api release built on the host; override with RPCNODE_POLKADOT_API_TAG in /etc/rpcnode/polkadot.env. */
    const val RELEASE_TAG = "v0.3.2"

    /** `rust:1.90.0` of the upstream Dockerfile at [RELEASE_TAG]; the crate is edition 2024 (needs Rust >= 1.85). */
    const val RUST_VERSION = "1.90.0"

    const val SCRIPT = "bin/rpcnode-polkadot-indexer.sh"
    const val LOG = "logs/polkadot-indexer.log"

    /** Public by design (it is the product), like the node RPC: the proxy / firewall in front decides who may call it. */
    const val BIND_HOST = "0.0.0.0"
    const val REQUEST_LIMIT_BYTES = 512_000
    const val KEEP_ALIVE_MS = 5_000
    const val BLOCK_FETCH_CONCURRENCY = 10
    const val LOG_LEVEL = "info"

    fun enabled(option: String?): Boolean = option?.trim()?.lowercase() == REST_API

    fun unitName(env: String): String = "rpcnode-polkadot-$env-indexer.service"

    /**
     * Environment of the API process. `/metrics` is served on the API port itself (not on a separate one), so the metrics
     * stay off: enabling them would publish them on the public port.
     */
    fun environment(rpcPort: Int, apiPort: Int): LinkedHashMap<String, String> = linkedMapOf(
        "SAS_SUBSTRATE_URL" to "ws://127.0.0.1:$rpcPort",
        "SAS_EXPRESS_BIND_HOST" to BIND_HOST,
        "SAS_EXPRESS_PORT" to apiPort.toString(),
        "SAS_EXPRESS_REQUEST_LIMIT" to REQUEST_LIMIT_BYTES.toString(),
        "SAS_EXPRESS_KEEP_ALIVE_TIMEOUT" to KEEP_ALIVE_MS.toString(),
        "SAS_EXPRESS_BLOCK_FETCH_CONCURRENCY" to BLOCK_FETCH_CONCURRENCY.toString(),
        "SAS_LOG_LEVEL" to LOG_LEVEL,
        "SAS_LOG_STRIP_ANSI" to "true",
        "SAS_METRICS_ENABLED" to "false",
    )

    fun script(env: SubstrateEnv, ports: SubstratePorts, nodeDir: String, stateDir: String, indexerDir: String, logFile: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polkadot", "indexer.sh.tmpl"),
            mapOf(
                "env" to shellQuote(env.id),
                "idx_dir" to shellQuote(indexerDir),
                "state_dir" to shellQuote(stateDir),
                "log_file" to shellQuote(logFile),
                "rpc_port" to ports.rpc.toString(),
                "release_tag" to RELEASE_TAG,
                "rust_version" to RUST_VERSION,
                "env_exports" to environment(ports.rpc, ports.indexer).entries
                    .joinToString("\n") { (k, v) -> "export $k=${shellQuote(v)}" },
            ),
        )

    fun unit(env: SubstrateEnv, nodeUnit: String, execStart: String): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("polkadot", "indexer.service.tmpl"),
            mapOf(
                "description" to "Polkadot REST API (polkadot-rest-api, built from source) (${env.id}) — RpcNode",
                "node_unit" to nodeUnit,
                "exec_start" to execStart,
            ),
        )

    /** The companion unit of a node, ready for [HostNodeLaunchSupport.installCustomUnits]. */
    fun companion(env: SubstrateEnv, nodeDir: String): Pair<String, String> =
        unitName(env.id) to unit(
            env = env,
            nodeUnit = HostNodeLaunchSupport.unitName("polkadot", env.id),
            execStart = "$nodeDir/$SCRIPT",
        )

    /** Safe single-token shell quoting for template injection. */
    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"
}
