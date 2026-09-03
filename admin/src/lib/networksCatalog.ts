import { useSyncExternalStore } from 'react'
import { api, type ClientConfigSpec, type DiskRoleDef, type NetworkCatalogItem } from '../api'

export type NetworkInfo = NetworkCatalogItem

let items: NetworkInfo[] = []
const listeners = new Set<() => void>()

function emit() {
  for (const cb of listeners) cb()
}

export function setNetworksCatalog(next: NetworkInfo[]) {
  items = next
  emit()
}

export function getNetworks(): NetworkInfo[] {
  return items
}

export function subscribeNetworks(cb: () => void) {
  listeners.add(cb)
  return () => {
    listeners.delete(cb)
  }
}

export function useNetworksCatalog(): NetworkInfo[] {
  return useSyncExternalStore(subscribeNetworks, getNetworks, getNetworks)
}

export async function loadNetworksCatalog(): Promise<NetworkInfo[]> {
  const res = await api.networks()
  const next = res.items || []
  setNetworksCatalog(next)
  return next
}

/** JBOD role defs from the in-memory networks catalog (network.yml diskRoles). */
export function diskRolesFromNetworksCatalog(
  network: string,
  env?: string,
): DiskRoleDef[] {
  const id = (network || '').toLowerCase()
  const hit = items.find((n) => n.id === id)
  const roles = hit?.disk_roles || []
  if (!roles.length) return []
  const envDetail = (hit?.env_details || []).find((e) => e.id === (env || '').toLowerCase())
  const hint = envDetail?.full_node_gib || envDetail?.disk_hint_gib
  return roles.map((r) => ({
    id: r.id,
    label: r.label,
    leaf: r.id,
    size_hint_gib: hint,
  }))
}

/** clientConfig bindings from the in-memory networks catalog. */
export function clientConfigFromNetworksCatalog(network: string): ClientConfigSpec | null {
  const id = (network || '').toLowerCase()
  const hit = items.find((n) => n.id === id)
  const cfg = hit?.client_config
  if (!cfg?.bindings?.length) return null
  return cfg
}

/** Default L1 parent URLs from network.yml `l1Parent` (Base / Arb). */
export function l1ParentFromNetworksCatalog(
  network: string,
  env?: string,
): { rpc: string; beacon: string; pickHelp: string } | null {
  const id = (network || '').toLowerCase()
  const hit = items.find((n) => n.id === id)
  const envDetail = (hit?.env_details || []).find((e) => e.id === (env || '').toLowerCase())
  const rpc = (envDetail?.l1_rpc_url || '').trim()
  const beacon = (envDetail?.l1_beacon_url || '').trim()
  const pickHelp = (envDetail?.l1_pick_help || '').trim()
  if (!rpc && !beacon && !pickHelp) return null
  return { rpc, beacon, pickHelp }
}

export function networkOptions(list: NetworkInfo[] = items): { value: string; label: string }[] {
  return list.map((n) => ({ value: n.id, label: n.label }))
}

export function envsForNetwork(
  network: string,
  list: NetworkInfo[] = items,
): { value: string; label: string }[] {
  const id = network.toLowerCase()
  const hit = list.find((n) => n.id === id)
  return (hit?.envs || []).map((env) => ({ value: env, label: env }))
}

export function networkLabel(id: string): string {
  const key = (id || '').toLowerCase()
  const hit = items.find((n) => n.id === key)
  if (hit?.label) return hit.label
  return id
}

/** Networks that allow only one env per host (spec.Chain Constraint). */
export function networkOneEnvPerHost(network: string): boolean {
  const id = network.toLowerCase()
  const hit = items.find((n) => n.id === id)
  return !!hit?.one_env_per_host
}
