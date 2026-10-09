import type { ClientRow } from '../api'
import type { NetworkInfo } from './networksCatalog'

/** Unique network → env set from the Clients list (synced at least once). */
export function clientNetworkEnvMap(rows: ClientRow[]): Map<string, Set<string>> {
  const map = new Map<string, Set<string>>()
  for (const row of rows) {
    const n = (row.network || '').toLowerCase().trim()
    const e = (row.env || '').trim()
    if (!n || !e) continue
    if (row.status === 'deleted') continue
    let set = map.get(n)
    if (!set) {
      set = new Set()
      map.set(n, set)
    }
    set.add(e)
  }
  return map
}

export function hasClientNetworkEnvs(rows: ClientRow[]): boolean {
  return clientNetworkEnvMap(rows).size > 0
}

/**
 * Networks the operator enabled on Networks (`api.networks()` catalog) that also have
 * at least one Clients env. Client pins alone do not surface a network here — otherwise
 * Add node listed chains that were never added on Networks.
 */
export function networksWithClients(catalog: NetworkInfo[], rows: ClientRow[]): NetworkInfo[] {
  const have = clientNetworkEnvMap(rows)
  const result: NetworkInfo[] = []

  for (const n of catalog) {
    const envs = have.get(n.id)
    if (!envs || envs.size === 0) continue
    const listed = (n.envs || []).filter((e) =>
      [...envs].some((x) => x.toLowerCase() === e.toLowerCase()),
    )
    const extra = [...envs].filter(
      (e) => !listed.some((x) => x.toLowerCase() === e.toLowerCase()),
    )
    result.push({ ...n, envs: [...listed, ...extra] })
  }

  return result
}

export function hasAddableNetworkEnvs(catalog: NetworkInfo[], rows: ClientRow[]): boolean {
  return networksWithClients(catalog, rows).length > 0
}
