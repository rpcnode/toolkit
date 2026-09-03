package rpcnode.toolkit.nodes.infrastructure.persistence

import org.jetbrains.exposed.sql.Table

object NodesTable : Table("nodes")
{
    val id = varchar("id", 64)
    val serverId = varchar("server_id", 64)
    val name = varchar("name", 255).default("")
    val network = varchar("network", 255)
    val env = varchar("env", 64)
    val publicPort = integer("public_port").default(0)
    val agentPort = integer("agent_port").default(0)
    val nodeHttpPort = integer("node_http_port").default(0)
    val p2pPort = integer("p2p_port").default(0)
    val status = varchar("status", 64).default("awaiting_ports")
    val diskLayoutJson = text("disk_layout_json").default("")
    val installOptionsJson = text("install_options_json").default("")
    val height = long("height").default(0)
    val heightAt = varchar("height_at", 64).default("")
    val networkHeight = long("network_height").default(0)
    val syncPct = double("sync_pct").default(-1.0)
    val sizeOnDisk = long("size_on_disk").default(-1)
    val clientVersion = varchar("client_version", 255).default("")
    val clientLatest = varchar("client_latest", 255).default("")
    val clientUpdateAvailable = integer("client_update_available").default(0)
    val createdAt = varchar("created_at", 64)
    val updatedAt = varchar("updated_at", 64)

    override val primaryKey = PrimaryKey(id)
}
