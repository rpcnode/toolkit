package rpcnode.toolkit.chains.opstack.infrastructure

/**
 * Pinned releases of the ethereum-optimism/optimism monorepo — the same pair `chains/optimism` runs (kept separate on purpose:
 * the family never touches that package). Every `chains/<id>/clients.yml` of the family pins the same tags (a test keeps them equal)
 * and the panel's build fleet compiles exactly them; a host without the prebuilt binary compiles them itself (run.sh.tmpl).
 *
 * op-reth and op-node must match each other AND carry the Superchain Registry of every family chain at its newest scheduled
 * hardfork (the registry is compiled in): bump both together after checking that on the newest tag.
 */
object OpstackToolchain
{
    const val RETH_TAG = "op-reth/v2.6.0"
    const val NODE_TAG = "op-node/v1.19.9"

    /** The monorepo's go.mod toolchain line at those tags (the op-node Dockerfile and the host build use it). */
    const val GO_VERSION = "1.26.6"

    /** `world-chain` release asset names are the same per tag; the panel keeps one tarball per CPU architecture. */
    const val WORLD_CHAIN_REPO = "worldcoin/world-chain"
}
