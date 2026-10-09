package rpcnode.toolkit.clients.infrastructure.persistence

import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable

object ClientPurgedTable : IdTable<String>("client_purged")
{
    override val id = varchar("network", 255).entityId()
    val purgedAt = varchar("purged_at", 64).default("")
    override val primaryKey = PrimaryKey(id)
}

class ClientPurgedEntity(id: EntityID<String>) : Entity<String>(id)
{
    companion object : EntityClass<String, ClientPurgedEntity>(ClientPurgedTable)

    var purgedAt by ClientPurgedTable.purgedAt
}
