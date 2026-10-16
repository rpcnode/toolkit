package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.cometbft.infrastructure.http.CometbftSnapshotResolver
import rpcnode.toolkit.chains.cometbft.infrastructure.http.CometbftSnapshotSource
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmRecipe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnit
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.CometbftCompanion
import rpcnode.toolkit.nodes.infrastructure.host.HostSystemdUnitTemplate

/**
 * Gravity L1 (AptosBFT consensus + Gravity Reth in ONE process) as a Public Full Node: `gravity_node node --full`.
 * Flags VERIFIED against cluster/templates/reth_config_pfn_prune.json.tpl + deploy.sh at v1.9.3 / v1.10.1-testnet; the toolkit
 * hardens authrpc + metrics to 127.0.0.1 (the template binds all interfaces), RPC connections 3000 (template 20000) and
 * namespaces eth,net,web3. The identity (`gravity_cli genesis generate-key`) is created once under `.toolkit/config` (0600).
 * Hardfork times are compiled into the binary: a node on an old release stops at the activation block.
 */
object GravityProfile
{
    const val HTTP_API = "eth,net,web3"
    const val RPC_MAX_CONNECTIONS = 3000
    /** docs: must be at least 10064 on production PFNs. */
    const val PRUNE_TX_LOOKUP = 10_064
    const val TXPOOL_MAX_ACCOUNT_SLOTS = 64

    private const val MAINNET_SEEDS = """    seeds:
      '0x38013b46c21388c3fd08ab32b86b478b3109125566d63c2da8fdc941dc474077':
        addresses:
          - '/dns/mainnet-rpc-p2p-1.gravity.xyz/tcp/6180/noise-ik/234aee14677a3d2198208ea72ca5e95ed75520df27f01f0c36220303ff78642f/handshake/0'
        role: PreferredUpstream
      '0x2d30cf69303d40e0efcdb0f3a6545d43b055e86729287f2f1328c001caeb2be4':
        addresses:
          - '/dns/mainnet-rpc-p2p-3.gravity.xyz/tcp/6180/noise-ik/0a71ef75482f617203f64b0d5e9e3a66361b5f35e9d709ef873c494cd76d2365/handshake/0'
        role: PreferredUpstream"""

    private const val TESTNET_SEEDS = """    seeds:
      '0xe7ddaced105af1c4381ca670cea01b0a8e3006c7e360a4f4e40e79fe06c29433':
        addresses:
          - '/dns/testnet-rpc-p2p-1.gravity.xyz/tcp/6195/noise-ik/86d3bd6c81fb6994bdbe8df40da28acf44433138cb09ad491c4d98637abd817a/handshake/0'
        role: PreferredUpstream"""

    fun configDir(ctx: EvmStartContext): Path = ctx.toolkitDir.resolve("config")

    fun storageDir(ctx: EvmStartContext): Path = ctx.dir("data")

    fun flags(ctx: EvmStartContext): List<String> = buildList {
        val storage = storageDir(ctx)
        add("--chain=${ctx.root.resolve("genesis.json")}")
        add("--full")
        add("--prune.transactionlookup.distance=$PRUNE_TX_LOOKUP")
        add("--datadir=${storage.resolve("reth")}")
        add("--datadir.static-files=${storage.resolve("reth")}")
        add("--gravity_node_config=${configDir(ctx).resolve("public_full_node.yaml")}")
        add("--http")
        add("--http.addr=0.0.0.0")
        add("--http.port=${ctx.port("http")}")
        add("--http.api=$HTTP_API")
        add("--http.corsdomain=*")
        add("--ws")
        add("--ws.addr=0.0.0.0")
        add("--ws.port=${ctx.port("ws")}")
        add("--ws.api=$HTTP_API")
        add("--ws.origins=*")
        add("--port=${ctx.port("p2p")}")
        add("--authrpc.addr=127.0.0.1")
        add("--authrpc.port=${ctx.port("engine")}")
        add("--metrics=127.0.0.1:${ctx.port("metrics")}")
        add("--log.file.filter=info")
        add("--log.stdout.filter=error")
        add("--log.file.directory=${ctx.logsDir.resolve("execution_logs")}/")
        add("--rpc.max-subscriptions-per-connection=20000")
        add("--rpc.max-connections=$RPC_MAX_CONNECTIONS")
        add("--txpool.max-new-pending-txs-notifications=1000000")
        add("--txpool.max-pending-txns=1000000")
        add("--txpool.pending-max-count=200000")
        add("--txpool.pending-max-size=512")
        add("--txpool.basefee-max-count=200000")
        add("--txpool.basefee-max-size=512")
        add("--txpool.queued-max-count=100000")
        add("--txpool.queued-max-size=256")
        add("--txpool.max-account-slots=$TXPOOL_MAX_ACCOUNT_SLOTS")
        add("--ipcdisable")
    }

    fun nodeConfig(ctx: EvmStartContext): String =
        HostSystemdUnitTemplate.render(
            HostSystemdUnitTemplate.load("gravity", "public_full_node.yaml.tmpl"),
            mapOf(
                "storage_dir" to storageDir(ctx).toString(),
                "config_dir" to configDir(ctx).toString(),
                "public_port" to ctx.port("consensus_p2p").toString(),
                "log_dir" to ctx.logsDir.toString(),
                "inspection_port" to ctx.port("inspection").toString(),
                "pfn_seeds_block" to ctx.env.fact("seeds"),
            ),
        )

    private object Recipe : EvmRecipe
    {
        override fun binaries(env: EvmEnv) = listOf(
            EvmBinary("gravity_node", "gravity_node-linux-*", EvmBinary.Kind.RAW),
            EvmBinary("gravity_cli", "gravity_cli-linux-*", EvmBinary.Kind.RAW),
        )

        override fun prepare(ctx: EvmStartContext): String?
        {
            val genesis = ctx.root.resolve("genesis.json")
            val waypoint = ctx.root.resolve("waypoint.txt")
            if (!Files.isRegularFile(genesis) || !Files.isRegularFile(waypoint))
            {
                return "genesis.json / waypoint.txt missing under ${ctx.root} — sync clients first"
            }
            return try
            {
                val config = configDir(ctx)
                Files.createDirectories(config)
                Files.createDirectories(storageDir(ctx))
                Files.createDirectories(ctx.logsDir.resolve("execution_logs"))
                Files.createDirectories(ctx.logsDir.resolve("consensus_log"))
                Files.copy(waypoint, config.resolve("waypoint.txt"), StandardCopyOption.REPLACE_EXISTING)
                val identity = config.resolve("identity.yaml")
                if (!Files.isRegularFile(identity))
                {
                    CometbftCompanion.run(
                        listOf(ctx.bin("gravity_cli").toString(), "genesis", "generate-key", "--output-file", identity.toString()),
                    )?.let { return it }
                }
                // the identity holds the node's private keys
                runCatching { Files.setPosixFilePermissions(identity, PosixFilePermissions.fromString("rw-------")) }
                Files.writeString(config.resolve("public_full_node.yaml"), nodeConfig(ctx))
                null
            }
            catch (e: Exception)
            {
                e.message ?: "prepare Gravity config failed"
            }
        }

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val unit = EvmUnit(
                description = "Gravity gravity_node PFN (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("gravity_node").toString(), "node"),
                flags = flags(ctx),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog()),
                environment = listOf("RUST_BACKTRACE=1", "BATCH_INSERT_TIME=20"),
                timeoutStopSec = 300,
            )
            return EvmUnits(primary = EvmUnitBodies.render("gravity", unit))
        }
    }

    const val MAINNET_SNAPSHOT = "https://gravity-snapshots.b-cdn.net/gravity-mainnet-data/latest.tar"
    const val TESTNET_SNAPSHOT = "https://gravity-snapshots.b-cdn.net/gravity-testnet-data/latest.tar"

    val PROFILE = EvmProfile(
        network = NetworkId.GRAVITY,
        recipe = Recipe,
        envs = listOf(
            EvmEnv("mainnet", "mainnet", 127_001, 41600, mapOf("seeds" to MAINNET_SEEDS)),
            EvmEnv("testnet", "testnet", 7_771_625, 41610, mapOf("seeds" to TESTNET_SEEDS)),
        ),
        portOffsets = mapOf("http" to 0, "ws" to 1, "p2p" to 2, "engine" to 3, "metrics" to 4, "inspection" to 5, "consensus_p2p" to 6),
        diskRoles = listOf("data"),
        // the official PFN data tarball (consensus_db/ quorumstoreDB/ reth/ at its top level) is unpacked in one pass into the storage dir
        snapshot = {
            CometbftSnapshotResolver(
                mapOf(
                    ("mainnet" to "pfn") to CometbftSnapshotSource.Fixed(MAINNET_SNAPSHOT),
                    ("testnet" to "pfn") to CometbftSnapshotSource.Fixed(TESTNET_SNAPSHOT),
                ),
                defaultType = "pfn",
            )
        },
        literalFlags = mapOf(
            "http-api" to listOf("--http.api", "--ws.api"),
            "rpc-max-connections" to listOf("--rpc.max-connections"),
            "prune-transactionlookup-distance" to listOf("--prune.transactionlookup.distance"),
            "txpool-max-account-slots" to listOf("--txpool.max-account-slots"),
        ),
    )
}
