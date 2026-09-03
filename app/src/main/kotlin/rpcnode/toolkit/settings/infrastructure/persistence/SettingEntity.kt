package rpcnode.toolkit.settings.infrastructure.persistence

import java.time.Instant
import org.jetbrains.exposed.dao.Entity
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IdTable
import org.jetbrains.exposed.sql.transactions.transaction
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

object SettingsTable : IdTable<String>("settings")
{
    override val id = varchar("key", 255).entityId()
    val settingValue = text("value").default("")
    val updatedAt = varchar("updated_at", 64).default("")
    override val primaryKey = PrimaryKey(id)
}

class SettingEntity(id: EntityID<String>) : Entity<String>(id)
{
    companion object : EntityClass<String, SettingEntity>(SettingsTable)

    var settingValue by SettingsTable.settingValue
    var updatedAt by SettingsTable.updatedAt
}

internal fun ToolkitDatabase.getSetting(key: String): String? = transaction(database) {
    SettingEntity.findById(key)?.settingValue
}

internal fun ToolkitDatabase.setSetting(key: String, value: String)
{
    transaction(database) {
        val row = SettingEntity.findById(key)
        val now = Instant.now().toString()
        if (row == null)
        {
            SettingEntity.new(key) {
                settingValue = value
                updatedAt = now
            }
        }
        else
        {
            row.settingValue = value
            row.updatedAt = now
        }
    }
}
