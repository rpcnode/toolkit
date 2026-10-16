package rpcnode.toolkit.chains.stacks.infrastructure.start

import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.chains.nonevm.infrastructure.NonEvmStartPlan
import rpcnode.toolkit.chains.stacks.infrastructure.StacksBurnchain
import rpcnode.toolkit.chains.stacks.infrastructure.StacksChains
import rpcnode.toolkit.chains.stacks.infrastructure.StacksSettings
import rpcnode.toolkit.nodes.application.start.ChainNodeStart
import rpcnode.toolkit.nodes.application.start.ChainNodeStartContext
import rpcnode.toolkit.nodes.application.start.ChainNodeStartPlan

/**
 * Stacks: `working_dir` is the `state` disk role. On mainnet the Bitcoin node comes from the `burnchain_*` wizard options
 * (host / RPC port / P2P port / user / password); the password travels as a launch arg to the agent, which writes it to a
 * 0600 `stacks.toml` and strips it from the recorded launch.
 */
class StacksNodeStart : ChainNodeStart
{
    override val networkId: NetworkId = NetworkId.STACKS

    override fun plan(ctx: ChainNodeStartContext): ChainNodeStartPlan
    {
        val env = StacksChains.env(ctx.env)
        val args = mutableListOf("--toolkit-env=${env.id}")
        NonEvmStartPlan.roleDir(ctx, "state").takeIf { it.isNotEmpty() }?.let { args += "--toolkit-state=$it" }
        if (env.local)
        {
            fun opt(key: String) = NonEvmStartPlan.option(ctx.installOptionsJson, key)
            args += "--toolkit-burn-host=${opt("burnchain_host") ?: StacksBurnchain.DEFAULT_HOST}"
            args += "--toolkit-burn-rpc-port=${opt("burnchain_rpc_port") ?: StacksBurnchain.DEFAULT_RPC_PORT}"
            args += "--toolkit-burn-p2p-port=${opt("burnchain_p2p_port") ?: StacksBurnchain.DEFAULT_P2P_PORT}"
            opt("burnchain_user")?.let { args += "--toolkit-burn-user=$it" }
            opt("burnchain_password")?.let { args += "--toolkit-burn-pass=$it" }
        }
        return NonEvmStartPlan.plan(
            entry = "bin/stacks-node",
            logFile = StacksSettings.RELATIVE_LOG,
            heightKind = "stacks_info",
            args = args,
        )
    }
}
