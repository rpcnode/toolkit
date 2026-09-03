package rpcnode.toolkit.networks.infrastructure.persistence

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable

object NetworksTable : IdTable<String>("networks")
{
    override val id = varchar("network", 255).entityId()
    val status = varchar("status", 32).default("pending")
    val addedAt = varchar("added_at", 64).default("")
    val notes = text("notes").default("")
    override val primaryKey = PrimaryKey(id)
}

class NetworkEntity(id: EntityID<String>) : Entity<String>(id)
{
    companion object : EntityClass<String, NetworkEntity>(NetworksTable)

    var status by NetworksTable.status
    var addedAt by NetworksTable.addedAt
    var notes by NetworksTable.notes
}
