package rpcnode.toolkit.chains.evmfamily.infrastructure

import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.BerachainProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.FlareProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.FluentProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.GnosisProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.GravityProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.JocProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.KaiaProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.MonadProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.SonicProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.TempoProfile
import rpcnode.toolkit.chains.evmfamily.infrastructure.profile.VanaProfile

/** The shipped EVM-family networks. Ports are the PORTS.md blocks; every other fact sits in the network's own profile. */
object EvmProfiles
{
    val ALL: List<EvmProfile> = listOf(
        GnosisProfile.PROFILE,
        JocProfile.PROFILE,
        FlareProfile.PROFILE,
        SonicProfile.PROFILE,
        KaiaProfile.PROFILE,
        VanaProfile.PROFILE,
        TempoProfile.PROFILE,
        BerachainProfile.PROFILE,
        FluentProfile.PROFILE,
        GravityProfile.PROFILE,
        MonadProfile.PROFILE,
    )
}
