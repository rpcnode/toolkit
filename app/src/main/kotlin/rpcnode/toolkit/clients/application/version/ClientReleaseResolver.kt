package rpcnode.toolkit.clients.application.version

import rpcnode.toolkit.catalog.domain.EnvId
import rpcnode.toolkit.clients.domain.model.ClientRelease

/**
 * One network's latest client release for an env. The implementation lives next to that
 * network (`chains/<id>/infrastructure`) — it already knows its own GitHub repo, pin, or
 * index. Callers look it up by [rpcnode.toolkit.catalog.domain.NetworkId] and never pass a
 * network id in. Returns null when this env has no client release or nothing was resolvable.
 */
fun interface ClientReleaseResolver
{
    suspend fun resolve(env: EnvId): ClientRelease?
}
