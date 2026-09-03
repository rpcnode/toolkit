package rpcnode.toolkit.clients.infrastructure.persistence

import org.jetbrains.exposed.sql.Table

/** Composite PK (network, env, program) doesn't fit Exposed's `IdTable`, so this is a plain `Table`. */
object ClientVersionsTable : Table("client_versions")
{
    val network = varchar("network", 255)
    val env = varchar("env", 64)
    val program = varchar("program", 255)
    val currentVersion = varchar("current_version", 255).default("")
    val currentTag = varchar("current_tag", 255).default("")
    val latestVersion = varchar("latest_version", 255).default("")
    val latestTag = varchar("latest_tag", 255).default("")
    val status = varchar("status", 32).default("wait")
    val sourceLabel = varchar("source", 255).default("")
    val url = text("url").default("")
    val notes = text("notes").default("")
    val skipReason = text("skip_reason").default("")
    val probeError = text("probe_error").default("")
    val probedAt = varchar("probed_at", 64).default("")
    val updatedAt = varchar("updated_at", 64).default("")

    override val primaryKey = PrimaryKey(network, env, program)
}
