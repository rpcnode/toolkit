package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmHostFacts
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
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.GethFlags

/**
 * Sonic (Sonic Labs L1): `sonicd` in rpc mode (the default) over a DB primed by `sonictool genesis` from a genesis file.
 * Flags VERIFIED at v2.2.5 (config/flags, cmd/sonicd, docs.soniclabs.com hardening + starter service file).
 * `--nat` is left out: it needs the public IP (INFERRED benefit), the default `any` is the client's own choice.
 */
object SonicProfile
{
    const val CACHE_MB = 12_000
    const val MAX_PEERS = 50
    const val BATCH_LIMIT = 1000
    const val HTTP_API = "eth,net,web3,sonic"
    /** Value of the Sonic docs' starter service file; also the number the docs give GOMEMLIMIT's 90 % for. */
    const val NOFILE = 934_073
    const val GOMEMLIMIT_PERCENT = 90

    fun flags(ctx: EvmStartContext): List<String> = buildList {
        add("--datadir=${ctx.dir("state").resolve("data")}")
        add("--cache=$CACHE_MB")
        add("--port=${ctx.port("p2p")}")
        add("--maxpeers=$MAX_PEERS")
        addAll(GethFlags.publicRpc(ctx.port("http"), ctx.port("ws"), HTTP_API, equals = true))
        add("--rpc.batch-request-limit=$BATCH_LIMIT")
        add("--ipcdisable")
        add("--metrics")
        add("--metrics.addr=127.0.0.1")
        add("--metrics.port=${ctx.port("metrics")}")
    }

    private object Recipe : EvmRecipe
    {
        override fun binaries(env: EvmEnv) = listOf(
            EvmBinary("sonicd", "sonicd-linux-*", EvmBinary.Kind.RAW),
            EvmBinary("sonictool", "sonictool-linux-*", EvmBinary.Kind.RAW),
        )

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val goMem = ctx.memTotalGiB?.let { EvmHostFacts.goMemLimitGiB(it, GOMEMLIMIT_PERCENT) }
            val unit = EvmUnit(
                description = "Sonic sonicd (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("sonicd").toString()),
                flags = flags(ctx),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog()),
                environment = listOfNotNull(goMem?.let { "GOMEMLIMIT=${it}GiB" }, "GOTRACEBACK=crash"),
                timeoutStopSec = 300,
                nofile = NOFILE,
            )
            return EvmUnits(primary = EvmUnitBodies.render("sonic", unit))
        }

        override fun snapshotScript(ref: EvmSnapshotRef, paths: EvmSnapshotPaths): String
        {
            val net = if (ref.env == "mainnet") "sonic" else "testnet"
            val url = "https://genesis.soniclabs.com/latest-$net-${ref.kind}.g"
            return EvmSnapshotScripts.render(
                "sonic",
                paths,
                units = listOf(EvmUnitBodies.unitName("sonic", ref.env)),
                vars = mapOf(
                    "sonictool" to EvmSnapshotScripts.q(paths.bins.getValue("sonictool").toString()),
                    "datadir" to EvmSnapshotScripts.q(paths.root.resolve("data").toString()),
                    "genesis_file" to EvmSnapshotScripts.q(paths.root.resolve(".toolkit/genesis.g").toString()),
                    "genesis_url" to url,
                    "genesis_md5_url" to "$url.md5",
                    "cache_mb" to CACHE_MB.toString(),
                ),
            )
        }
    }

    val PROFILE = EvmProfile(
        network = NetworkId.SONIC,
        recipe = Recipe,
        envs = listOf(
            EvmEnv("mainnet", "sonic", 146, 42440),
            EvmEnv("testnet", "testnet", 14601, 42450),
        ),
        portOffsets = mapOf("http" to 0, "ws" to 1, "p2p" to 2, "metrics" to 4),
        diskRoles = listOf("state"),
        snapshot = {
            EvmSnapshotSentinelResolver(
                "sonic",
                mapOf("mainnet" to setOf("pruned", "archive"), "testnet" to setOf("pruned", "archive")),
                defaultKind = "pruned",
            )
        },
        literalFlags = mapOf(
            "cache-mb" to listOf("--cache"),
            "maxpeers" to listOf("--maxpeers"),
            "http-api" to listOf("--http.api", "--ws.api"),
            "rpc-batch-request-limit" to listOf("--rpc.batch-request-limit"),
        ),
        nofile = NOFILE,
    )
}
