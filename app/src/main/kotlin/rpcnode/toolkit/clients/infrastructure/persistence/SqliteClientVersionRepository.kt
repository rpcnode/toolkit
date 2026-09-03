package rpcnode.toolkit.clients.infrastructure.persistence

import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.catalog.domain.NetworkId
import rpcnode.toolkit.clients.domain.model.ClientVersionPin
import rpcnode.toolkit.clients.domain.repository.ClientVersionRepository
import rpcnode.toolkit.shared.infrastructure.persistence.ToolkitDatabase

class SqliteClientVersionRepository(
    private val db: ToolkitDatabase,
) : ClientVersionRepository
{
    override suspend fun list(): List<ClientVersionPin> = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ClientVersionsTable.selectAll().map { it.toDomain() }
        }
    }

    override suspend fun find(network: NetworkId, env: EnvId, program: String): ClientVersionPin? = withContext(Dispatchers.IO) {
        transaction(db.database) {
            rowFor(network, env, program)?.toDomain()
        }
    }

    override suspend fun applyProbe(pin: ClientVersionPin) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            val existing = rowFor(pin.network, pin.env, pin.program) ?: return@transaction
            val now = Instant.now().toString()
            ClientVersionsTable.update({
                (ClientVersionsTable.network eq pin.network.value) and
                    (ClientVersionsTable.env eq pin.env.value) and
                    (ClientVersionsTable.program eq pin.program)
            }) {
                it[latestVersion] = pin.latestVersion
                it[latestTag] = pin.latestTag
                it[sourceLabel] = pin.source
                it[url] = pin.url
                it[notes] = pin.notes
                it[skipReason] = pin.skipReason
                it[probeError] = pin.probeError
                it[probedAt] = pin.probedAt.ifBlank { now }
                it[status] = pin.status.value
                it[updatedAt] = now
            }
            Unit
        }
    }

    override suspend fun applySynced(pin: ClientVersionPin) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            val now = Instant.now().toString()
            val existing = rowFor(pin.network, pin.env, pin.program)
            if (existing == null)
            {
                ClientVersionsTable.insert {
                    it[network] = pin.network.value
                    it[env] = pin.env.value
                    it[program] = pin.program
                    it[currentVersion] = pin.currentVersion
                    it[currentTag] = pin.currentTag
                    it[latestVersion] = pin.latestVersion
                    it[latestTag] = pin.latestTag
                    it[sourceLabel] = pin.source
                    it[url] = pin.url
                    it[notes] = pin.notes
                    it[skipReason] = pin.skipReason
                    it[probeError] = pin.probeError
                    it[probedAt] = pin.probedAt.ifBlank { now }
                    it[status] = pin.status.value
                    it[updatedAt] = now
                }
            }
            else
            {
                ClientVersionsTable.update({
                    (ClientVersionsTable.network eq pin.network.value) and
                        (ClientVersionsTable.env eq pin.env.value) and
                        (ClientVersionsTable.program eq pin.program)
                }) {
                    it[currentVersion] = pin.currentVersion
                    it[currentTag] = pin.currentTag
                    it[latestVersion] = pin.latestVersion
                    it[latestTag] = pin.latestTag
                    it[sourceLabel] = pin.source
                    it[url] = pin.url
                    it[notes] = pin.notes
                    it[skipReason] = pin.skipReason
                    it[probeError] = pin.probeError
                    it[probedAt] = pin.probedAt.ifBlank { now }
                    it[status] = pin.status.value
                    it[updatedAt] = now
                }
            }
            Unit
        }
    }

    override suspend fun deleteEnv(network: NetworkId, env: EnvId) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ClientVersionsTable.deleteWhere {
                SqlExpressionBuilder.run {
                    (ClientVersionsTable.network eq network.value) and (ClientVersionsTable.env eq env.value)
                }
            }
            Unit
        }
    }

    override suspend fun deleteNetwork(network: NetworkId) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ClientVersionsTable.deleteWhere { SqlExpressionBuilder.run { ClientVersionsTable.network eq network.value } }
            Unit
        }
    }

    override suspend fun isPurged(network: NetworkId): Boolean = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ClientPurgedEntity.findById(network.value) != null
        }
    }

    override suspend fun markPurged(network: NetworkId) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            val now = Instant.now().toString()
            val existing = ClientPurgedEntity.findById(network.value)
            if (existing == null)
            {
                ClientPurgedEntity.new(network.value) { purgedAt = now }
            }
            else
            {
                existing.purgedAt = now
            }
            Unit
        }
    }

    override suspend fun clearPurged(network: NetworkId) = withContext(Dispatchers.IO) {
        transaction(db.database) {
            ClientPurgedEntity.findById(network.value)?.delete()
            Unit
        }
    }

    private fun rowFor(network: NetworkId, env: EnvId, program: String): ResultRow? =
        ClientVersionsTable.selectAll().where {
            (ClientVersionsTable.network eq network.value) and
                (ClientVersionsTable.env eq env.value) and
                (ClientVersionsTable.program eq program)
        }.firstOrNull()

    private fun ResultRow.toDomain() = ClientVersionPin(
        network = NetworkId.parse(this[ClientVersionsTable.network]) ?: error("invalid network in client_versions"),
        env = EnvId.parse(this[ClientVersionsTable.env]) ?: error("invalid env in client_versions"),
        program = this[ClientVersionsTable.program],
        currentVersion = this[ClientVersionsTable.currentVersion],
        currentTag = this[ClientVersionsTable.currentTag],
        latestVersion = this[ClientVersionsTable.latestVersion],
        latestTag = this[ClientVersionsTable.latestTag],
        source = this[ClientVersionsTable.sourceLabel],
        url = this[ClientVersionsTable.url],
        notes = this[ClientVersionsTable.notes],
        skipReason = this[ClientVersionsTable.skipReason],
        probeError = this[ClientVersionsTable.probeError],
        probedAt = this[ClientVersionsTable.probedAt],
        updatedAt = this[ClientVersionsTable.updatedAt],
    )
}
