package rpcnode.toolkit.networks.infrastructure.persistence

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.transactions.transaction
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.networks.domain.model.Network
import rpcnode.toolkit.networks.domain.model.NetworkStatus
import rpcnode.toolkit.networks.domain.repository.NetworkRepository
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteNetworkRepository(
    private val db: ToolkitDatabase,
) : NetworkRepository
{
    override suspend fun list(): List<Network> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            NetworkEntity.all().map { it.toDomain() }
        }
    }

    override suspend fun upsert(network: NetworkId, status: NetworkStatus, notes: String) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            val now = Instant.now().toString()
            val existing = NetworkEntity.findById(network.value)
            if (existing == null)
            {
                NetworkEntity.new(network.value) {
                    this.status = status.value
                    this.addedAt = now
                    this.notes = notes
                }
            }
            else
            {
                existing.status = status.value
                existing.addedAt = now
                existing.notes = notes
            }
            Unit
        }
    }

    override suspend fun delete(network: NetworkId) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            NetworkEntity.findById(network.value)?.delete()
            Unit
        }
    }

    private fun NetworkEntity.toDomain() = Network(
        network = NetworkId.parse(id.value) ?: error("invalid network id in networks table: ${id.value}"),
        status = NetworkStatus.parse(status) ?: NetworkStatus.PENDING,
        addedAt = addedAt,
        notes = notes,
    )
}
