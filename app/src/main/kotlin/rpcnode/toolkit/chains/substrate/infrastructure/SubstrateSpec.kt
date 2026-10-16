package rpcnode.toolkit.chains.substrate.infrastructure

import rpcnode.toolkit.catalog.domain.NetworkId

/**
 * Node type of a Substrate node. Pruning is stored in the database on its first start, so changing it later means
 * wiping the DB — the starter refuses instead of letting the node fail with a pruning-mode error.
 */
enum class SubstrateMode(val id: String)
{
    PRUNED("pruned"),
    ARCHIVE("archive"),
    ;

    companion object
    {
        fun parse(raw: String?): SubstrateMode =
            if (raw?.trim()?.lowercase() == ARCHIVE.id) ARCHIVE else PRUNED
    }
}

/** `<base-path>/chains/<chainDirId>/{paritydb|db}/full` is where the node keeps its database. */
enum class SubstrateDb(val flag: String, val dirName: String)
{
    PARITYDB("paritydb", "paritydb"),
    ROCKSDB("rocksdb", "db"),
}

/** One env of a Substrate relay chain: the `--chain` value, its database directory id and its block of catalog ports. */
data class SubstrateEnv(
    val id: String,
    /** `--chain` value (a built-in spec). */
    val chain: String,
    /** The `id` field of the chain spec — the directory under `chains/`. `westend` is `westend2`. */
    val chainDirId: String,
    val basePort: Int,
    /** `system_properties.ss58Format` of the chain (VERIFIED on the public RPCs 2026-10-14): addresses of the history indexer are printed with it. */
    val ss58Prefix: Int,
    /** Database of the archive node of this env: Parity publishes no paritydb archive of Polkadot or Paseo. */
    val archiveDb: SubstrateDb,
    /** `snapshots.polkadot.io` listing name per mode. */
    val snapshotNames: Map<SubstrateMode, String>,
)

/**
 * Catalog ports of one env (`clients.yml`): `+0` RPC (HTTP + WS), `+2` p2p, `+4` metrics, `+5` the optional REST API,
 * `+6` the history indexer's GraphQL API and `+7` its processor metrics (both loopback, install option `indexer=history`).
 */
data class SubstratePorts(val rpc: Int, val p2p: Int, val metrics: Int, val indexer: Int, val historyApi: Int, val historyMetrics: Int)

/** Everything that differs per Substrate network; the classes of this package read a spec, they never branch on the id. */
data class SubstrateSpec(
    val network: NetworkId,
    val binary: String,
    /** PVF workers that must sit beside [binary] at the same version (relay-chain nodes only). */
    val workers: List<String>,
    val envs: List<SubstrateEnv>,
    val logName: String = network.value,
)
{
    fun env(raw: String): SubstrateEnv = envs.firstOrNull { it.id == raw.trim().lowercase() } ?: envs.first()

    fun ports(env: SubstrateEnv): SubstratePorts =
        SubstratePorts(
            rpc = env.basePort,
            p2p = env.basePort + 2,
            metrics = env.basePort + 4,
            indexer = env.basePort + 5,
            historyApi = env.basePort + 6,
            historyMetrics = env.basePort + 7,
        )

    /** Every file `bin/` must hold. */
    val binaries: List<String> get() = listOf(binary) + workers

    val relativeLog: String get() = "logs/$logName.log"
}

object SubstrateSpecs
{
    val POLKADOT = SubstrateSpec(
        network = NetworkId.POLKADOT,
        binary = "polkadot",
        workers = listOf("polkadot-prepare-worker", "polkadot-execute-worker"),
        envs = listOf(
            SubstrateEnv(
                id = "mainnet",
                chain = "polkadot",
                chainDirId = "polkadot",
                basePort = 42200,
                ss58Prefix = 0,
                archiveDb = SubstrateDb.ROCKSDB,
                snapshotNames = mapOf(
                    SubstrateMode.PRUNED to "polkadot-paritydb-state-blocks-pruned",
                    SubstrateMode.ARCHIVE to "polkadot-rocksdb-archive",
                ),
            ),
            SubstrateEnv(
                id = "paseo",
                chain = "paseo",
                chainDirId = "paseo",
                basePort = 42210,
                ss58Prefix = 42,
                archiveDb = SubstrateDb.ROCKSDB,
                snapshotNames = mapOf(
                    SubstrateMode.PRUNED to "paseo-paritydb-prune",
                    SubstrateMode.ARCHIVE to "paseo-rocksdb-archive",
                ),
            ),
            SubstrateEnv(
                id = "westend",
                chain = "westend",
                chainDirId = "westend2",
                basePort = 42220,
                ss58Prefix = 42,
                archiveDb = SubstrateDb.PARITYDB,
                snapshotNames = mapOf(
                    SubstrateMode.PRUNED to "westend-paritydb-prune",
                    SubstrateMode.ARCHIVE to "westend-paritydb-archive",
                ),
            ),
        ),
    )
}
