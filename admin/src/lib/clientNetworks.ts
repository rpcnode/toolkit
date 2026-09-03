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

/** Catalog networks/envs that already appear on Clients. Extra client-only ids are appended. */
export function networksWithClients(catalog: NetworkInfo[], rows: ClientRow[]): NetworkInfo[] {
  const have = clientNetworkEnvMap(rows)
  const result: NetworkInfo[] = []
  const seen = new Set<string>()

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
    seen.add(n.id)
  }

  for (const [id, envs] of have) {
    if (seen.has(id)) continue
    result.push({ id, label: id, envs: [...envs] })
  }

  return result
}
