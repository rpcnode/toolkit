package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.cometbft.infrastructure.TomlSetting
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmRecipe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotPaths
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotRef
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmSnapshotScripts
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnit
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.http.BerachainSnapshotResolver
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.CometbftCompanion
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.RethFlags

/**
 * Berachain: `bera-reth` (execution, reth fork) + `beacond` (BeaconKit consensus, CometBFT). Both live under ONE disk role:
 * `<data>/reth` and `<data>/beacond` (the official snapshot pair is restored into those two). Flags VERIFIED against
 * berachain/guides `run-reth.sh` / `setup-beacond.sh` and reth v1.11.4 docs; config keys against beacon-kit
 * `testing/networks/<chain id>/{app,config}.toml` at v1.4.1 / v1.4.2-rc.0.
 * Version pair (docs evm-execution page): mainnet beacon-kit v1.4.1, Bepolia v1.4.2-rc.0 (pinned), bera-reth v1.4.4 on both.
 */
object BerachainProfile
{
    const val HTTP_API = "eth,net,web3,rpc"

    fun reth(ctx: EvmStartContext): Path = ctx.dir("data").resolve("reth")

    fun beacondHome(ctx: EvmStartContext): Path = ctx.dir("data").resolve("beacond")

    /** `--full` (pruned) unless the archive flavour was chosen; the pruned snapshot is the default. */
    fun archive(ctx: EvmStartContext): Boolean = ctx.options.snapshot == "archive"

    fun rethFlags(ctx: EvmStartContext, bootnodes: String): List<String> = buildList {
        add("--datadir ${reth(ctx)}")
        add("--chain ${ctx.env.chain}")
        if (!archive(ctx)) add("--full")
        if (bootnodes.isNotEmpty()) add("--bootnodes $bootnodes")
        add("--authrpc.addr 127.0.0.1")
        add("--authrpc.port ${ctx.port("engine")}")
        add("--authrpc.jwtsecret ${checkNotNull(ctx.jwt)}")
        addAll(RethFlags.p2p(ctx.port("p2p")))
        addAll(RethFlags.metrics(ctx.port("metrics")))
        addAll(RethFlags.publicRpc(ctx.port("http"), ctx.port("ws"), HTTP_API))
        addAll(RethFlags.capacity())
        add("--ipcdisable")
        add("--engine.persistence-threshold 0")
        add("--engine.memory-block-buffer-target 0")
    }

    /** `app.toml` / `config.toml` values applied on every Start (keys VERIFIED in the official files). */
    fun appToml(ctx: EvmStartContext): List<TomlSetting> = listOf(
        TomlSetting("app.toml", "beacon-kit.engine", "rpc-dial-url", "http://127.0.0.1:${ctx.port("engine")}"),
        TomlSetting("app.toml", "beacon-kit.engine", "jwt-secret-path", checkNotNull(ctx.jwt).toString()),
        TomlSetting("app.toml", "beacon-kit.kzg", "trusted-setup-path", beacondHome(ctx).resolve("config/kzg-trusted-setup.json").toString()),
    )

    fun configToml(ctx: EvmStartContext): List<TomlSetting> = listOf(
        TomlSetting("config.toml", "", "moniker", CometbftCompanion.moniker(ctx.nodeId)),
        TomlSetting("config.toml", "", "proxy_app", "tcp://127.0.0.1:${ctx.port("cl_proxy")}"),
        TomlSetting("config.toml", "rpc", "laddr", "tcp://127.0.0.1:${ctx.port("cl_rpc")}"),
        TomlSetting("config.toml", "p2p", "laddr", "tcp://0.0.0.0:${ctx.port("consensus_p2p")}"),
        TomlSetting("config.toml", "instrumentation", "prometheus", "true", TomlSetting.Kind.RAW),
        TomlSetting("config.toml", "instrumentation", "prometheus_listen_addr", "127.0.0.1:${ctx.port("cl_metrics")}"),
    )

    /** Execution bootnodes: every `enode://…` of the release's `el-bootnodes.txt` (region headings and blank lines dropped). */
    fun parseBootnodes(text: String): String =
        Regex("""enode://[0-9a-fA-F]+@[^\s,]+""").findAll(text).map { it.value }.distinct().joinToString(",")

    private object Recipe : EvmRecipe
    {
        override val usesJwt = true

        override fun binaries(env: EvmEnv) = listOf(
            EvmBinary("bera-reth", "bera-reth-*.tar.gz", EvmBinary.Kind.TAR_MEMBER),
            EvmBinary("beacond", "beacond-*.tar.gz", EvmBinary.Kind.TAR_MEMBER, member = "beacond-*"),
        )

        override fun prepare(ctx: EvmStartContext): String?
        {
            val home = beacondHome(ctx)
            val cfg = home.resolve("config")
            return try
            {
                Files.createDirectories(reth(ctx))
                Files.createDirectories(home)
                for (f in listOf("genesis.json", "kzg-trusted-setup.json", "app.toml", "config.toml", "el-bootnodes.txt"))
                {
                    if (!Files.isRegularFile(ctx.root.resolve(f))) return "$f missing under ${ctx.root} — sync clients first"
                }
                val seeded = cfg.resolve(".rpcnode-seeded")
                if (!Files.isRegularFile(seeded))
                {
                    // official setup-beacond.sh: init, then the network's files over the generated ones (priv_validator_key stays)
                    if (!Files.isRegularFile(cfg.resolve("priv_validator_key.json")))
                    {
                        CometbftCompanion.run(
                            listOf(
                                ctx.bin("beacond").toString(), "init", CometbftCompanion.moniker(ctx.nodeId),
                                "--beacon-kit.chain-spec", ctx.env.fact("chain_spec"), "--home", home.toString(),
                            ),
                        )?.let { return it }
                    }
                    Files.createDirectories(cfg)
                    for (f in listOf("genesis.json", "kzg-trusted-setup.json", "app.toml", "config.toml"))
                    {
                        Files.copy(ctx.root.resolve(f), cfg.resolve(f), StandardCopyOption.REPLACE_EXISTING)
                    }
                    Files.writeString(seeded, "seeded from ${ctx.env.chain} files\n")
                }
                CometbftCompanion.patch(cfg.resolve("app.toml"), appToml(ctx))
                CometbftCompanion.patch(cfg.resolve("config.toml"), configToml(ctx))
                ctx.scratch["bootnodes"] = parseBootnodes(Files.readString(ctx.root.resolve("el-bootnodes.txt")))
                null
            }
            catch (e: Exception)
            {
                e.message ?: "prepare beacond home failed"
            }
        }

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val el = EvmUnit(
                description = "Berachain bera-reth (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("bera-reth").toString(), "node"),
                flags = rethFlags(ctx, ctx.scratch["bootnodes"].orEmpty()),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog()),
                timeoutStopSec = 300,
            )
            val cl = EvmUnit(
                description = "Berachain beacond (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("beacond").toString(), "start"),
                flags = listOf("--home ${beacondHome(ctx)}"),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog("consensus")),
                after = listOf(ctx.primaryUnit),
                requires = listOf(ctx.primaryUnit),
                timeoutStopSec = 300,
            )
            return EvmUnits(
                primary = EvmUnitBodies.render("berachain", el),
                companions = listOf(ctx.companionUnit("consensus") to EvmUnitBodies.render("berachain", cl, EvmUnitBodies.COMPANION_TEMPLATE)),
            )
        }

        override fun snapshotScript(ref: EvmSnapshotRef, paths: EvmSnapshotPaths): String =
            EvmSnapshotScripts.render(
                "berachain",
                paths,
                units = listOf(EvmUnitBodies.companionName("berachain", "consensus", ref.env), EvmUnitBodies.unitName("berachain", ref.env)),
                vars = mapOf(
                    "reth_dir" to EvmSnapshotScripts.q(paths.root.resolve("reth").toString()),
                    "beacond_home" to EvmSnapshotScripts.q(paths.root.resolve("beacond").toString()),
                    "scratch" to EvmSnapshotScripts.q(paths.root.resolve(".toolkit").toString()),
                    "el_url" to EvmSnapshotScripts.q(ref.param("el")),
                    "el_sha256" to EvmSnapshotScripts.q(ref.param("elsha")),
                    "cl_url" to EvmSnapshotScripts.q(ref.param("cl")),
                    "cl_sha256" to EvmSnapshotScripts.q(ref.param("clsha")),
                    "block" to ref.param("block").filter { it.isDigit() },
                ),
            )
    }

    val PROFILE = EvmProfile(
        network = NetworkId.BERACHAIN,
        recipe = Recipe,
        envs = listOf(
            EvmEnv("mainnet", "mainnet", 80094, 41150, mapOf("chain_spec" to "mainnet")),
            EvmEnv("bepolia", "bepolia", 80069, 41160, mapOf("chain_spec" to "testnet")),
        ),
        portOffsets = mapOf(
            "http" to 0, "ws" to 1, "p2p" to 2, "engine" to 3, "metrics" to 4,
            "cl_rpc" to 5, "consensus_p2p" to 6, "cl_metrics" to 7, "cl_proxy" to 8,
        ),
        diskRoles = listOf("data"),
        snapshot = { BerachainSnapshotResolver() },
        literalFlags = mapOf(
            "http-api" to listOf("--http.api", "--ws.api"),
            "rpc-max-connections" to listOf("--rpc.max-connections"),
            "rpc-max-response-size" to listOf("--rpc.max-response-size"),
            "rpc-max-blocks-per-filter" to listOf("--rpc.max-blocks-per-filter"),
            "engine-persistence-threshold" to listOf("--engine.persistence-threshold"),
            "engine-memory-block-buffer-target" to listOf("--engine.memory-block-buffer-target"),
        ),
    )
}
