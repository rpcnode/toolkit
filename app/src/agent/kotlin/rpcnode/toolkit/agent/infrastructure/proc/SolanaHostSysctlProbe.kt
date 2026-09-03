package rpcnode.toolkit.agent.infrastructure.proc

import rpcnode.toolkit.agent.application.sysctl.HostSysctlProbe
import rpcnode.toolkit.agent.domain.model.HostSysctlSnapshot
import rpcnode.toolkit.chains.solana.infrastructure.SolanaSysctlTuning
import rpcnode.toolkit.chains.solana.infrastructure.proc.SolanaHostTune

/** Reads Agave/Anza sysctl knobs from `/proc/sys` (chain types live in main). */
class SolanaHostSysctlProbe : HostSysctlProbe
{
    override fun snapshot(): HostSysctlSnapshot =
        HostSysctlSnapshot(
            current = SolanaHostTune.readCurrent(),
            recommended = SolanaSysctlTuning.recommended.asMap(),
            installOptionKeys = SolanaSysctlTuning.OPTION_BY_KEY,
        )
}
