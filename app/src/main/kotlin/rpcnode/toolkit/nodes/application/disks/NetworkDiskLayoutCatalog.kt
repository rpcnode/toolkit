package rpcnode.toolkit.nodes.application.disks

import rpcnode.toolkit.networks.domain.model.NetworkEnvFacts
import rpcnode.toolkit.networks.domain.model.NetworkFacts
import rpcnode.toolkit.nodes.domain.model.DiskRoleDef

/** Network-specific JBOD roles and layout hints from `chains/<id>/network.yml`. */
object NetworkDiskLayoutCatalog
{
    private val DEFAULT_LAYOUT_RULES = listOf(
        "Prefer separate NVMe as JBOD (not one RAID volume).",
        "Put roles on different disks when ≥2 NVMe/SSD data mounts exist.",
        "Single disk → all roles under /data/rpcnode/<network>/<env>/.",
    )

    fun diskRoles(facts: NetworkFacts?, envFacts: NetworkEnvFacts?): List<DiskRoleDef>
    {
        if (facts == null || facts.diskRoles.isEmpty())
        {
            return emptyList()
        }
        val hint = envFacts?.fullNodeGiB ?: envFacts?.diskHintGiB
        return facts.diskRoles.map {
            DiskRoleDef(
                id = it.id,
                label = it.label,
                leaf = it.id,
                sizeHintGiB = hint,
            )
        }
    }

    fun layoutRules(facts: NetworkFacts?): List<String>
    {
        val notes = facts?.diskNotes.orEmpty().filter { it.isNotBlank() }
        if (notes.isNotEmpty())
        {
            return notes
        }
        return DEFAULT_LAYOUT_RULES
    }
}
