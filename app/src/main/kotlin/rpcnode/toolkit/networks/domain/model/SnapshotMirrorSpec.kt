package rpcnode.toolkit.networks.domain.model

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId

/**
 * Official snapshot mirror for one network/env/type — from `clients/<network>.yml` → snapshots.
 */
data class SnapshotMirrorSpec(
    val network: NetworkId,
    val env: EnvId,
    val typeId: String,
    val mirror: String,
    val filename: String,
    /** `listing` = directory scrape; `dated` = HEAD backupYYYYMMDD/<filename>. */
    val discover: String = "listing",
)
