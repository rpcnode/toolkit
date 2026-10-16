package rpcnode.toolkit.chains.evmfamily.infrastructure.profile

import java.nio.file.Files
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmBinary
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmEnv
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmRecipe
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmStartContext
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnit
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnitBodies
import rpcnode.toolkit.chains.evmfamily.infrastructure.EvmUnits
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.CometbftCompanion
import rpcnode.toolkit.chains.evmfamily.infrastructure.recipe.GethFlags
import rpcnode.toolkit.chains.ethereum.infrastructure.http.EthereumGethArtifactUrlResolver

/**
 * Vana L1 (mainnet 1480, Moksha 14800): geth (execution) + a Prysm fork (consensus), flags of the official docker-compose
 * (vana-com/vana v2.4.0, VERIFIED). Mainnet needs the PATCHED geth (`geth-v1.16.8-vana9228152`, a canonical-state override at
 * block 9228152 per its release note; the official compose still pins stock v1.16.8, so this rests on the release note) and
 * the Vana Prysm build (`--clear-deposit-contract`); Moksha runs stock geth 1.16.8 and the SAME Prysm build.
 * RPC limits are hardened against the compose (batch 1000 / 25 MB / evm timeout 5 s instead of 5000 / 125 MB / unlimited).
 * No snapshot in v1: Vana publishes daily GCS tarballs (README) but their network and state scheme could not be confirmed.
 */
object VanaProfile
{
    const val HTTP_API = "eth,net,web3"
    const val BATCH_LIMIT = 1000
    const val BATCH_RESPONSE_MAX = 25_000_000
    const val EVM_TIMEOUT = "5s"
    const val TX_FEE_CAP = 1000

    private val EL_MAINNET = listOf(
        "enode://9926a7a1282a3847d2c52934fd142406ffc8cd2d49c8e6ee3600fd4e0f0fbb3a7b3cf5e4714aeebe09f76d8ffbe724cece0238e474aa9ec51ac0cd3450f61db5@34.118.106.29:0?discport=30303",
        "enode://e6a602c5c7af78af08049ee11cc6ca71f21c501808d190e64266d98f961e92fac604b85bda8e3991358f8843ae9cac9672b5ee1360b712f1a91e48c9b0c6b956@34.55.228.240:0?discport=30303",
    )
    private val EL_MOKSHA = listOf(
        "enode://3cd214419423663983a39a7fb2fe647a6c0073d2083b61bb9e6bca1c3916f903d2f66c5ca162f341512d15eea9821a0325122fff4940f4e3b20dc0a755b2e6a1@34.45.237.218:30303",
        "enode://270aeac2143799a80d267a18187ce7b6402fa26923d7f219803a5c0551771e0f965997a3c0dcc524deeef70710a9742d02786682eec8fafc51a61e4aaebe7555@34.57.71.47:0?discport=30303",
        "enode://fe16f66483ba1861a7d1cc2ddc51728a29212beef1d85fdec7d48a4761225b401f321037e1b6ba9e51665a9e80aa9cd3cc26c5a241b1e7ecf96640c0370a2ece@34.56.57.89:0?discport=30303",
    )
    private val CL_MAINNET = listOf(
        "enr:-JG4QADt449GVstc-Q72CuDdsR2PScST4zD5SoUq3YUn2N7qJYP1O68e9_1WXQ3mqev-nCY6zPjFTOZCZ_fqso2FUy-GAZy50yU8gmlkgnY0gmlwhCJ2ah2Jc2VjcDI1NmsxoQIBWrGWWBoYpfJjchHBmSMuRlGvEM4beeY32b5SbtYctoN0Y3CCMsiDdWRwgi7g",
        "enr:-JG4QCsyHa-9t-E8YgAy42iOxxqoSGxgd2ieSDV4z8x9Arc0CJ7pokFeHfVqpI5_EGZCsBJyXj_XJS_7nNcsDfqVhcKGAZy5ZyAKgmlkgnY0gmlwhCI35PCJc2VjcDI1NmsxoQNG9SoNUCMq-UssobXhFJPq3fvniXkup0e8ce4QtA7C6YN0Y3CCMsiDdWRwgi7g",
    )
    private val CL_MOKSHA = listOf(
        "enr:-MK4QADyybq9LFHlHNi1aTa8kPPuDyH_Vn7U4qMV3ZQnKNsmH9zGjMCojmPR94bBd_7CJR-bK8Vq8hzwoXNn_2jKvI2GAZOOGYRPh2F0dG5ldHOIAAAAAAAAAAaEZXRoMpBBTsvKYAAAOP__________gmlkgnY0gmlwhCIt7dqJc2VjcDI1NmsxoQKJt2DraBATjIVg63_dWHM2RW-a9slPRJqnqgCuq9tTGIhzeW5jbmV0cw-DdGNwgjLIg3VkcIIu4A",
        "enr:-LG4QLAlGy9NT2ew1cBixH3WFQk1jaTOivLOrfMM75kIjjgmGbi2j1GY97HOLcd9GqP19gwvKPMJdlfsr6YrC32zoiWGAZRMD74fh2F0dG5ldHOIAAAAAAAAAACEZXRoMpD1pf1CAAAAAP__________gmlkgnY0gmlwhCI5Ry-Jc2VjcDI1NmsxoQPC4PLtODaKgsBNDt1SaKQWffPC0NJJlJ4SOvsWn7mfJ4N1ZHCCLuA",
        "enr:-LG4QFiKGeFqS0TghnK4Ug8BRfgEh0SAUtZmMEfvuN02z_aKFzeh5xyjw0nB4l4tZGLX10kN2pLFgr_AvDpaU61Cpi6GAZRRolgmh2F0dG5ldHOIAAAAAAAAAACEZXRoMpD1pf1CAAAAAP__________gmlkgnY0gmlwhCI4OVmJc2VjcDI1NmsxoQJsi9Euy2Jlruzg3ZHZ3lA90-ZUyox0yunh9erIsA4bX4N1ZHCCLuA",
    )

    fun gethFlags(ctx: EvmStartContext): List<String> = buildList {
        add("--networkid=${ctx.env.chainId}")
        add("--datadir=${ctx.dir("execution").resolve("datadir")}")
        add("--port=${ctx.port("p2p")}")
        add("--discovery.port=${ctx.port("p2p")}")
        add("--syncmode=full")
        add("--state.scheme=path")
        add("--gcmode=full")
        add("--bootnodes=${ctx.env.fact("el_bootnodes")}")
        addAll(GethFlags.publicRpc(ctx.port("http"), ctx.port("ws"), HTTP_API, equals = true))
        add("--rpc.batch-request-limit=$BATCH_LIMIT")
        add("--rpc.batch-response-max-size=$BATCH_RESPONSE_MAX")
        add("--rpc.evmtimeout=$EVM_TIMEOUT")
        add("--rpc.txfeecap=$TX_FEE_CAP")
        add("--authrpc.addr=127.0.0.1")
        add("--authrpc.port=${ctx.port("engine")}")
        add("--authrpc.jwtsecret=${checkNotNull(ctx.jwt)}")
        add("--authrpc.vhosts=localhost")
        add("--metrics")
        add("--metrics.addr=127.0.0.1")
        add("--metrics.port=${ctx.port("metrics")}")
        add("--ipcdisable")
    }

    fun prysmFlags(ctx: EvmStartContext): List<String> = buildList {
        add("--accept-terms-of-use")
        add("--chain-id=${ctx.env.chainId}")
        add("--datadir=${ctx.dir("consensus").resolve("prysm")}")
        add("--genesis-state=${ctx.root.resolve("genesis.ssz")}")
        add("--chain-config-file=${ctx.root.resolve("config.yml")}")
        add("--contract-deployment-block=0")
        add("--clear-deposit-contract")
        add("--execution-endpoint=http://127.0.0.1:${ctx.port("engine")}")
        add("--jwt-secret=${checkNotNull(ctx.jwt)}")
        add("--checkpoint-sync-url=${ctx.env.fact("checkpoint")}")
        add("--genesis-beacon-api-url=${ctx.env.fact("checkpoint")}")
        add("--min-sync-peers=1")
        add("--block-batch-limit=256")
        add("--block-batch-limit-burst-factor=8")
        add("--blob-batch-limit=256")
        add("--blob-batch-limit-burst-factor=8")
        add("--rpc-host=127.0.0.1")
        add("--rpc-port=${ctx.port("consensus_rpc")}")
        add("--grpc-gateway-host=127.0.0.1")
        add("--grpc-gateway-port=${ctx.port("beacon")}")
        add("--p2p-tcp-port=${ctx.port("consensus_p2p")}")
        add("--p2p-udp-port=${ctx.port("consensus_udp")}")
        add("--monitoring-host=127.0.0.1")
        add("--monitoring-port=${ctx.port("consensus_metrics")}")
        ctx.env.fact("cl_bootnodes").split(',').filter { it.isNotBlank() }.forEach { add("--bootstrap-node=$it") }
    }

    private object Recipe : EvmRecipe
    {
        override val usesJwt = true

        override fun binaries(env: EvmEnv): List<EvmBinary>
        {
            val geth = if (env.id == "mainnet")
            {
                // the patched release asset is a raw static binary
                EvmBinary("geth", "geth-linux-amd64", EvmBinary.Kind.RAW)
            }
            else
            {
                EvmBinary("geth", "geth-linux-*.tar.gz", EvmBinary.Kind.TAR_MEMBER, member = "geth")
            }
            return listOf(geth, EvmBinary("beacon-chain", "beacon-chain", EvmBinary.Kind.RAW))
        }

        override fun prepare(ctx: EvmStartContext): String?
        {
            for (f in listOf("genesis.json", "genesis.ssz", "config.yml"))
            {
                if (!Files.isRegularFile(ctx.root.resolve(f))) return "$f missing under ${ctx.root} — sync clients first"
            }
            val datadir = ctx.dir("execution").resolve("datadir")
            return try
            {
                Files.createDirectories(datadir)
                Files.createDirectories(ctx.dir("consensus").resolve("prysm"))
                // geth needs the genesis written once (path scheme); a restored or earlier datadir already has chaindata
                if (!Files.isDirectory(datadir.resolve("geth").resolve("chaindata")))
                {
                    CometbftCompanion.run(
                        listOf(
                            ctx.bin("geth").toString(), "--datadir", datadir.toString(), "--state.scheme=path",
                            "init", ctx.root.resolve("genesis.json").toString(),
                        ),
                    )
                }
                else
                {
                    null
                }
            }
            catch (e: Exception)
            {
                e.message ?: "prepare Vana datadirs failed"
            }
        }

        override fun units(ctx: EvmStartContext): EvmUnits
        {
            val el = EvmUnit(
                description = "Vana geth (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("geth").toString()),
                flags = gethFlags(ctx),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog()),
                killSignal = "SIGINT",
                timeoutStopSec = 600,
            )
            val cl = EvmUnit(
                description = "Vana Prysm beacon-chain (${ctx.env.id}) — RpcNode",
                command = listOf(ctx.bin("beacon-chain").toString()),
                flags = prysmFlags(ctx),
                workDir = ctx.root,
                logFile = ctx.root.resolve(ctx.profile.relativeLog("consensus")),
                after = listOf(ctx.primaryUnit),
                requires = listOf(ctx.primaryUnit),
                timeoutStopSec = 300,
            )
            return EvmUnits(
                primary = EvmUnitBodies.render("vana", el),
                companions = listOf(ctx.companionUnit("consensus") to EvmUnitBodies.render("vana", cl, EvmUnitBodies.COMPANION_TEMPLATE)),
            )
        }
    }

    val PROFILE = EvmProfile(
        network = NetworkId.VANA,
        recipe = Recipe,
        envs = listOf(
            EvmEnv(
                "mainnet", "mainnet", 1480, 42650,
                mapOf("el_bootnodes" to EL_MAINNET.joinToString(","), "cl_bootnodes" to CL_MAINNET.joinToString(","), "checkpoint" to "https://rpc.vana.org"),
            ),
            EvmEnv(
                "moksha", "moksha", 14800, 42660,
                mapOf("el_bootnodes" to EL_MOKSHA.joinToString(","), "cl_bootnodes" to CL_MOKSHA.joinToString(","), "checkpoint" to "https://archive.moksha.vana.org"),
            ),
        ),
        portOffsets = mapOf(
            "http" to 0, "ws" to 1, "p2p" to 2, "engine" to 3, "metrics" to 4,
            "beacon" to 5, "consensus_p2p" to 6, "consensus_udp" to 7, "consensus_rpc" to 8, "consensus_metrics" to 9,
        ),
        diskRoles = listOf("execution", "consensus"),
        // Moksha's stock geth comes from the go-ethereum store (urlTemplate "gethstore"), like Ethereum's
        artifactUrls = { EthereumGethArtifactUrlResolver(network = NetworkId.VANA) },
        literalFlags = mapOf(
            "http-api" to listOf("--http.api", "--ws.api"),
            "rpc-batch-request-limit" to listOf("--rpc.batch-request-limit"),
            "rpc-batch-response-max-size" to listOf("--rpc.batch-response-max-size"),
            "rpc-evmtimeout" to listOf("--rpc.evmtimeout"),
            "rpc-txfeecap" to listOf("--rpc.txfeecap"),
            "state-scheme" to listOf("--state.scheme"),
        ),
    )
}
