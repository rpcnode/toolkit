package rpcnode.toolkit.agent.application.disks

import rpcnode.toolkit.agent.domain.model.HostDiskInventory

fun interface HostDiskProbe
{
    fun inventory(): HostDiskInventory
}

class GetHostDisksUseCase(
    private val probe: HostDiskProbe,
)
{
    operator fun invoke(): HostDiskInventory = probe.inventory()
}
