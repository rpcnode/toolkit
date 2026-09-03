package rpcnode.toolkit.networks.domain.model

/** One JBOD role + recommended production media class. */
data class NetworkDiskRoleFacts(
    val id: String,
    val label: String,
    val media: String,
)

/**
 * One binding from shipped client config → panel variable
 * (`clientConfig.bindings` in chains/<id>/network.yml).
 */
data class ClientConfigBindingFacts(
    /** HOCON path or INI key written into the rendered config. */
    val path: String,
    val source: String,
    /** Operator-facing explanation on the Start step. */
    val description: String? = null,
    val role: String? = null,
    val option: String? = null,
    val value: String? = null,
    val relative: String? = null,
    val optional: Boolean = false,
    val default: String? = null,
    /** For [source] `snapshot_kind`: value by snapshot type id or kind (full, lite, archive). */
    val map: Map<String, String> = emptyMap(),
    /**
     * When set, the binding is emitted only if [install_options][whenInstallOption] equals
     * [whenInstallOptionValue] (default `"1"`). Used for optional catalog ports on Start.
     */
    val whenInstallOption: String? = null,
    val whenInstallOptionValue: String? = null,
    /** Optional Start-step live probe (e.g. L1 eth_rpc / beacon_genesis). */
    val testConnect: ClientConfigTestConnectFacts? = null,
)

/**
 * Live “Test connect” action for a Start binding (`testConnect` in network.yml).
 * [kind] selects the panel probe (`eth_rpc`, `beacon_genesis`).
 */
data class ClientConfigTestConnectFacts(
    val kind: String,
    val label: String = "Test connect",
    /** Operator-facing instructions shown next to the button. */
    val help: String? = null,
)

/**
 * How to render the network's shipped client template with panel values.
 * Loaded from `clientConfig` in chains/<id>/network.yml — display / Start preview first;
 * full render use cases come later.
 */
data class ClientConfigFacts(
    val program: String = "",
    val format: String = "",
    val template: String? = null,
    val templates: Map<String, String> = emptyMap(),
    val envSections: Map<String, String> = emptyMap(),
    val bindings: List<ClientConfigBindingFacts> = emptyList(),
)

/**
 * Wizard snapshot / node flavor for one env (`snapshotTypes` in chains/<id>/network.yml).
 * [id] is persisted as `install_options.snapshot` and the CDN folder name.
 * [kind] is the product class: full | lite | archive.
 * [destLeaf] optional last path segment under the data mount (e.g. lite → litefullnode).
 */
data class SnapshotTypeFacts(
    val id: String,
    val kind: String,
    val label: String,
    val hint: String? = null,
    val diskGiB: Double? = null,
    val default: Boolean = false,
    val destLeaf: String? = null,
)

/**
 * One env's disk / host / snapshot facts (plan, full/archive footprint, recommended CPU/RAM).
 * Informational only — never drives install behaviour.
 */
data class NetworkEnvFacts(
    val id: String,
    val label: String? = null,
    val diskHintGiB: Double? = null,
    val fullNodeGiB: Double? = null,
    val archiveGiB: Double? = null,
    val cpuCores: Double? = null,
    val memoryGiB: Double? = null,
    val snapshot: String? = null,
    /**
     * How snapshot data is obtained when [snapshot] is required/optional.
     * `via_node` — chain process downloads itself (Agave); no toolkit aria2 URL.
     * Empty / omitted — official mirror / CDN (`SnapshotResolver`).
     */
    val snapshotBootstrap: String? = null,
    val snapshotTypes: List<SnapshotTypeFacts> = emptyList(),
    /** Public tip endpoints for this env (`publicTip.urls` in chains/<id>/network.yml). */
    val publicTipUrls: List<String> = emptyList(),
    /**
     * Optional Beacon API tip URLs (`publicTip.beaconUrls`) — used by L2 parents (Base / Arb)
     * when picking a public L1 beacon.
     */
    val publicTipBeaconUrls: List<String> = emptyList(),
    /**
     * Default Ethereum L1 execution RPC for L2 parents (Base / Arb).
     * Shown on Start as editable install_options.l1_rpc.
     */
    val l1RpcUrl: String? = null,
    /**
     * Default Ethereum L1 beacon / blob API for L2 parents.
     * Shown on Start as editable install_options.l1_beacon.
     */
    val l1BeaconUrl: String? = null,
    /** Operator hint for L1 parent picker on Start (`l1Parent.pickHelp`). */
    val l1PickHelp: String? = null,
)

/**
 * Static reference facts for one network — display name, envs, disk layout, per-env sizing.
 * Loaded from `chains/<id>/network.yml`. The directory name is the network id; this file is the mapping.
 */
data class NetworkFacts(
    val label: String? = null,
    /**
     * Host path segment under `/data/rpcnode/` when different from the network id
     * (e.g. arb → arbitrum). Empty → use the catalog id.
     */
    val dataRoot: String? = null,
    val envs: List<NetworkEnvFacts> = emptyList(),
    val diskRoles: List<NetworkDiskRoleFacts> = emptyList(),
    val diskMedia: String? = null,
    val diskNotes: List<String> = emptyList(),
    val oneEnvPerHost: Boolean = false,
    val clientConfig: ClientConfigFacts? = null,
)
