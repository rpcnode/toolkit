package rpcnode.toolkit.clients.application

import rpcnode.toolkit.clients.domain.model.ClientVersionPin

/**
 * Holds probe results before the first successful download persists a row in
 * [rpcnode.toolkit.clients.domain.repository.ClientVersionRepository] — the Add-client flow reads
 * this while a network/env has never been synced.
 */
interface ClientPreviewStore
{
    fun get(key: ClientProgramKey): ClientVersionPin?

    fun put(key: ClientProgramKey, pin: ClientVersionPin)

    fun clear(key: ClientProgramKey)
}
