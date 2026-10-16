package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import java.nio.file.Files
import java.nio.file.Path
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmHostBinaries
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmRecipe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotPaths
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotScripts
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnit
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.EvmSnapshotSentinelResolver
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.MonadCruft
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.MonadHost
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.MonadNodeToml

/**
 * Monad (category-labs): execution (`monad`), consensus (`monad-node`) and `monad-rpc` around a TrieDB on a raw block device.
 * The units are the ones of the monad 0.16.3 / 0.16.4 packages (identical, checked in the .deb and in the repo tags) with every
 * path moved under node_dir and the catalog ports; the vendor's `User=monad`, `/home/monad`, `/opt/monad`, `/dev/triedb` udev
 * link and OTEL collector are not used. Flags not in the packaged units (`--triedb-stats-path`, `--db-stats-file`, only in
 * repo master) are left out on purpose.
 * INFERRED (checked at Start, not on a host): `--rpc-addr/--rpc-port/--ws-port` of monad-rpc (names found in the binary,
 * `MonadHost.checkRpcBinary` verifies them against `--help`), `--otel-endpoint` being optional (it is not in the binaries'
 * required-argument lists), the output keys of `monad-sign-name-record` (`self_*`, found in the binary).
 */
object MonadProfile
{
    const val EXECUTION = "monad"
    const val CONSENSUS = "monad-node"
    const val RPC = "monad-rpc"
    const val DEVICE_OPTION = "triedb_device"
    const val IP_OPTION = "public_ip"
    const val DEB = "monad-amd64.deb"
    const val NODE_TOML_TEMPLATE = "monad-full-node.toml"
    const val NOFILE = 1_048_576
    const val SQ_THREAD_CPU = 1
    const val STATESYNC_SQ_THREAD_CPU = 8
    const val IMPORT_SQ_THREAD_CPU = 15
    const val EXECUTION_CPUS = "1-7"
    const val CONSENSUS_CPUS = "8-11"
    const val RPC_CPUS = "12-15"
    const val UNIT_DEVICE_FALLBACK = "/dev/triedb"
    private const val HUGEPAGES_1G = "echo 4 > /sys/devices/system/node/node0/hugepages/hugepages-1048576kB/nr_hugepages"
    private const val HUGEPAGES_2M = "echo 2048 > /sys/devices/system/node/node0/hugepages/hugepages-2048kB/nr_hugepages"

    private fun cpuList(range: String): String
    {
        val (a, b) = range.split('-').map { it.toInt() }
        return (a..b).joinToString(",")
    }

    private fun device(ctx: EvmStartContext): String = ctx.options.extra[DEVICE_OPTION].orEmpty().ifBlank { UNIT_DEVICE_FALLBACK }

    private fun libs(ctx: EvmStartContext): Path = EvmHostBinaries.libDir(ctx.root)

    private fun envFile(ctx: EvmStartContext): Path = ctx.root.resolve(MonadHost.ENV_FILE)

    private fun cpuLines(range: String) = listOf("AllowedCPUs=$range", "CPUAffinity=${cpuList(range)}")

    fun hugepagesUnit(ctx: EvmStartContext): String = ctx.companionUnit("hugepages")

    /** Hourly ledger clean-up: `rpcnode-monad-cruft-<env>.service` (oneshot) fired by the `.timer` of the same name. */
    fun cruftService(env: String): String = EvmUnitBodies.companionName("monad", "cruft", env)

    fun cruftTimer(env: String): String = cruftService(env).removeSuffix(".service") + ".timer"

    fun executionFlags(ctx: EvmStartContext): List<String> = listOf(
        "--chain ${ctx.env.chain}",
        "--db ${device(ctx)}",
        "--block_db ${ctx.dir("state").resolve("ledger")}",
        "--statesync ${ctx.dir("state").resolve("statesync.sock")}",
        "--sq_thread_cpu $SQ_THREAD_CPU",
        "--log_level INFO",
    )

    fun consensusFlags(ctx: EvmStartContext): List<String>
    {
        val state = ctx.dir("state")
        return listOf(
            "--secp-identity ${state.resolve("config/id-secp")}",
            "--bls-identity ${state.resolve("config/id-bls")}",
            "--node-config ${state.resolve("config/node.toml")}",
            "--forkpoint-config ${state.resolve("config/forkpoint/forkpoint.toml")}",
            "--wal-path ${state.resolve("wal")}",
            "--mempool-ipc-path ${state.resolve("mempool.sock")}",
            "--control-panel-ipc-path ${state.resolve("controlpanel.sock")}",
            "--statesync-ipc-path ${state.resolve("statesync.sock")}",
            "--ledger-path ${state.resolve("ledger")}",
            "--triedb-path ${device(ctx)}",
            "--statesync-sq-thread-cpu $STATESYNC_SQ_THREAD_CPU",
            "--record-metrics-interval-seconds 1",
            "--validators-path ${state.resolve("config/validators/validators.toml")}",
            "--persisted-peers-path ${state.resolve("config/peers.toml")}",
            "--keystore-password \"\${KEYSTORE_PASSWORD}\"",
        )
    }

    fun rpcFlags(ctx: EvmStartContext): List<String> = listOf(
        "--ipc-path ${ctx.dir("state").resolve("mempool.sock")}",
        "--triedb-path ${device(ctx)}",
        "--allow-unprotected-txs",
        "--node-config ${ctx.dir("state").resolve("config/node.toml")}",
        "--rpc-addr 0.0.0.0",
        "--rpc-port ${ctx.port("http")}",
        "--ws-port ${ctx.port("ws")}",
    )

    private fun libEnv(ctx: EvmStartContext) = "LD_LIBRARY_PATH=${libs(ctx)}"

    private object Recipe : EvmRecipe
    {
        override fun binaries(env: EvmEnv): List<EvmBinary> =
            listOf(EXECUTION, CONSENSUS, RPC, "monad-mpt", "monad-cli", "monad-keystore", "monad-sign-name-record")
                .map { EvmBinary(it, DEB, EvmBinary.Kind.DEB_BUNDLE, member = it) }

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val hugepages = hugepagesUnit(ctx)
            fun unit(
                description: String,
                bin: String,
                flags: List<String>,
                cpus: String,
                log: String,
                restart: String,
                env: List<String> = emptyList(),
                extra: List<String> = emptyList(),
            ) = EvmUnit(
                description = description,
                command = listOf(ctx.bin(bin).toString()),
                flags = flags,
                workDir = ctx.root,
                logFile = ctx.root.resolve(log),
                environment = listOf(libEnv(ctx)) + env,
                after = listOf(hugepages),
                requires = listOf(hugepages),
                timeoutStopSec = 300,
                nofile = NOFILE,
                restart = restart,
                serviceExtra = cpuLines(cpus) + extra,
            )

            // execution and consensus never restart on their own (official units: a crashed node is looked at by the operator)
            val execution = unit(
                "Monad execution (${ctx.env.id}) — RpcNode", EXECUTION, executionFlags(ctx), EXECUTION_CPUS,
                ctx.profile.relativeLog(), restart = "no",
            )
            val consensus = unit(
                "Monad consensus (${ctx.env.id}) — RpcNode", CONSENSUS, consensusFlags(ctx), CONSENSUS_CPUS,
                ctx.profile.relativeLog("bft"), restart = "no",
                env = listOf(
                    "RUST_LOG=debug,h2=warn,tower=warn,opentelemetry_sdk=warn,opentelemetry-otlp=warn",
                    "REMOTE_VALIDATORS_URL=${ctx.env.fact("validators_url")}",
                    "REMOTE_FORKPOINT_URL=${ctx.env.fact("forkpoint_url")}",
                ),
                extra = listOf("EnvironmentFile=${envFile(ctx)}"),
            )
            val rpc = unit(
                "Monad RPC (${ctx.env.id}) — RpcNode", RPC, rpcFlags(ctx), RPC_CPUS,
                ctx.profile.relativeLog("rpc"), restart = "always",
                env = listOf("RUST_LOG=info,monad_ipc=debug"),
            )
            val pages = EvmUnit(
                description = "Monad huge pages (${ctx.env.id}) — RpcNode",
                command = listOf("/bin/sh", "-c"),
                flags = listOf("'$HUGEPAGES_2M'"),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog("hugepages")),
                restart = "no",
                serviceExtra = listOf("ExecStart=/bin/sh -c '$HUGEPAGES_1G'"),
            )
            val cruft = EvmUnit(
                description = "Monad ledger clean-up (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.root.resolve(MonadCruft.SCRIPT).toString()),
                flags = emptyList(),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog("cruft")),
                restart = "no",
            )
            val timer = EvmUnit(
                description = "Monad ledger clean-up, hourly (${ctx.env.id}) — RpcNode",
                command = emptyList(),
                flags = emptyList(),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog("cruft")),
            )
            return EvmUnits(
                primary = EvmUnitBodies.render("monad", execution),
                companions = listOf(
                    hugepages to EvmUnitBodies.render("monad", pages, "hugepages.service.tmpl"),
                    ctx.companionUnit("bft") to EvmUnitBodies.render("monad", consensus),
                    ctx.companionUnit("rpc") to EvmUnitBodies.render("monad", rpc),
                    cruftService(ctx.env.id) to EvmUnitBodies.render("monad", cruft, "cruft.service.tmpl"),
                    cruftTimer(ctx.env.id) to EvmUnitBodies.render("monad", timer, "cruft.timer.tmpl"),
                ),
                startCompanionsFirst = true,
            )
        }

        override fun prepare(ctx: EvmStartContext): String?
        {
            MonadHost.checkCpus()?.let { return it }
            val dev = ctx.options.extra[DEVICE_OPTION].orEmpty()
            MonadHost.checkDevice(dev, ctx.root, initialised = true)?.let { return it }
            val libDir = libs(ctx)
            MonadHost.checkRpcBinary(ctx.bin(RPC), libDir)?.let { return it }
            val state = ctx.dir("state")
            val ports = ctx.profile.ports(ctx.env)
            val p2p = ports.getValue("p2p")
            val auth = ports.getValue("auth_udp")
            val direct = ports.getValue("direct_udp")
            return try
            {
                for (d in listOf("config/forkpoint", "config/validators", "ledger")) Files.createDirectories(state.resolve(d))
                val password = MonadHost.ensurePassword(ctx.root).getOrElse { return "cannot create the keystore password: ${it.message}" }
                MonadHost.ensureKeystores(ctx.root, state, { ctx.bin(it) }, libDir, password)?.let { return it }
                val ip = MonadHost.publicIp(ctx.options.extra[IP_OPTION])
                    ?: return "cannot find the public IPv4 of this host — set the 'Public IP' option (the node's signed name record advertises it)"
                writeNodeToml(ctx, state, ip, password, p2p, auth, direct)?.let { return it }
                for ((name, url) in listOf(
                    "config/forkpoint/forkpoint.toml" to ctx.env.fact("forkpoint_url"),
                    "config/validators/validators.toml" to ctx.env.fact("validators_url"),
                ))
                {
                    val file = state.resolve(name)
                    if (!Files.isRegularFile(file) && !MonadHost.download(url, file)) return "cannot download $url"
                }
                val script = ctx.root.resolve(MonadCruft.SCRIPT)
                Files.createDirectories(script.parent)
                Files.writeString(script, MonadCruft.script(state))
                script.toFile().setExecutable(true, false)
                MonadHost.tuneKernel()
            }
            catch (e: Exception)
            {
                e.message ?: "preparing the Monad node failed"
            }
        }

        /** node.toml from the synced vendor template; an up-to-date signed record is kept, a changed IP / port is re-signed with seq + 1. */
        private fun writeNodeToml(ctx: EvmStartContext, state: Path, ip: String, password: String, p2p: Int, auth: Int, direct: Int): String?
        {
            val target = state.resolve("config/node.toml")
            val envId = ctx.env.id
            val existing = runCatching { Files.readString(target) }.getOrNull()
            val advertised = existing?.let { MonadNodeToml.peerValue(it, "self_address") }
            val wanted = if (envId == "testnet") ip else "$ip:$p2p"
            val seq = existing?.let { MonadNodeToml.peerValue(it, "self_record_seq_num")?.toIntOrNull() }
            val portsOk = existing?.let {
                MonadNodeToml.peerValue(it, "self_auth_port") == auth.toString() &&
                    (envId != "testnet" || (MonadNodeToml.peerValue(it, "self_tcp_port") == p2p.toString() &&
                        MonadNodeToml.peerValue(it, "self_direct_udp_auth_port") == direct.toString()))
            } == true
            if (existing != null && advertised == wanted && portsOk && MonadNodeToml.peerValue(existing, "self_name_record_sig") != null) return null

            val template = ctx.root.resolve(NODE_TOML_TEMPLATE)
            if (!Files.isRegularFile(template)) return "$NODE_TOML_TEMPLATE missing under ${ctx.root} — sync clients (monad) first"
            val signed = MonadHost.signNameRecord(
                envId, ctx.bin("monad-sign-name-record"), libs(ctx), ip, p2p, auth, direct,
                state.resolve("config/id-secp"), password, (seq ?: 0) + 1,
            ).getOrElse { return it.message }
            val peer = LinkedHashMap(MonadNodeToml.peerSection(envId, p2p, auth, direct))
            // the printed values win: they are what the signature covers
            signed.forEach { (k, v) -> peer[k] = v }
            val name = "full_rpcnode-" + ctx.nodeId.filter { it.isLetterOrDigit() || it == '-' }.take(16).ifEmpty { "node" }
            val body = MonadNodeToml.patch(
                Files.readString(template),
                name,
                mapOf(
                    "peer_discovery" to peer,
                    "network" to MonadNodeToml.networkSection(envId, p2p, auth, direct),
                ),
            )
            Files.createDirectories(target.parent)
            Files.writeString(target, body)
            return null
        }

        override fun snapshotScript(ref: EvmSnapshotRef, paths: EvmSnapshotPaths): String
        {
            val env = profile().env(ref.env)
            val device = ref.param(DEVICE_OPTION)
            // the Snapshot step runs before Start: the same device checks, and the script only ever touches this device
            MonadHost.checkDevice(device, paths.root, initialised = false)?.let { throw IllegalStateException(it) }
            MonadHost.checkCpus()?.let { throw IllegalStateException(it) }
            val units = listOf(
                EvmUnitBodies.unitName("monad", env.id),
                EvmUnitBodies.companionName("monad", "hugepages", env.id),
                EvmUnitBodies.companionName("monad", "bft", env.id),
                EvmUnitBodies.companionName("monad", "rpc", env.id),
                cruftTimer(env.id),
                cruftService(env.id),
            )
            return EvmSnapshotScripts.render(
                "monad",
                paths,
                units = units,
                vars = mapOf(
                    "mpt" to EvmSnapshotScripts.q(paths.bins.getValue("monad-mpt").toString()),
                    "cli" to EvmSnapshotScripts.q(paths.bins.getValue("monad-cli").toString()),
                    "libs" to EvmSnapshotScripts.q(EvmHostBinaries.libDir(paths.root).toString()),
                    "device" to EvmSnapshotScripts.q(device),
                    "device_marker" to EvmSnapshotScripts.q(paths.root.resolve(MonadHost.DEVICE_MARKER).toString()),
                    "state_dir" to EvmSnapshotScripts.q(paths.root.toString()),
                    "snap_dir" to EvmSnapshotScripts.q(paths.root.resolve("snapshots").toString()),
                    "host" to env.fact("snapshot_host"),
                    "forkpoint_url" to env.fact("forkpoint_url"),
                    "validators_url" to env.fact("validators_url"),
                    "import_cpu" to IMPORT_SQ_THREAD_CPU.toString(),
                ),
            )
        }
    }

    private fun profile(): EvmProfile = PROFILE

    val PROFILE: EvmProfile = EvmProfile(
        network = NetworkId.MONAD,
        recipe = Recipe,
        envs = listOf(
            EvmEnv(
                "mainnet", "monad_mainnet", 143, 42020,
                mapOf(
                    "snapshot_host" to "https://bucket.monadinfra.com/snapshots/mainnet",
                    "forkpoint_url" to "https://bucket.monadinfra.com/forkpoint/mainnet/forkpoint.toml",
                    "validators_url" to "https://bucket.monadinfra.com/validators/mainnet/validators.toml",
                ),
            ),
            EvmEnv(
                "testnet", "monad_testnet", 10143, 42030,
                mapOf(
                    "snapshot_host" to "https://bucket.monadinfra.com/snapshots/testnet",
                    "forkpoint_url" to "https://bucket.monadinfra.com/forkpoint/testnet/forkpoint.toml",
                    "validators_url" to "https://bucket.monadinfra.com/validators/testnet/validators.toml",
                ),
            ),
        ),
        portOffsets = mapOf("http" to 0, "ws" to 1, "p2p" to 2, "auth_udp" to 3, "direct_udp" to 4),
        diskRoles = listOf("state"),
        logName = "monad",
        snapshot = {
            EvmSnapshotSentinelResolver(
                "monad",
                mapOf("mainnet" to setOf("full"), "testnet" to setOf("full")),
                defaultKind = "full",
            )
        },
        literalFlags = mapOf(
            "statesync-sq-thread-cpu" to listOf("--statesync-sq-thread-cpu"),
            "sq-thread-cpu" to listOf("--sq_thread_cpu"),
        ),
        nofile = NOFILE,
        optionKeys = listOf(DEVICE_OPTION, IP_OPTION),
    )
}
