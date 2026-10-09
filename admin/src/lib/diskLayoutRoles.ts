import type { DiskRoleDef, MultiDiskLayoutPlan } from '../api'
import { pathOnDataMount } from './networkPaths'

/** Legacy wizard ids before multi_disk_roles loaded from the tip. */
const LEGACY_ROLE_ORDER = ['primary', 'secondary', 'tertiary', 'quaternary'] as const

function rolePlacements(layout: MultiDiskLayoutPlan): Array<{ id: string; dir?: string; mount?: string; label?: string }> {
  const roles = layout.roles
  if (Array.isArray(roles)) {
    return roles.filter((r) => r?.id)
  }
  const map =
    layout.roles_map ||
    (roles && typeof roles === 'object' ? (roles as Record<string, { dir?: string; mount?: string }>) : {})
  return Object.entries(map).map(([id, v]) => ({ id, dir: v?.dir, mount: v?.mount }))
}

function catalogIds(catalog: DiskRoleDef[]): Set<string> {
  return new Set(catalog.map((r) => r.id))
}

/** Remap primary/secondary onto catalog role ids by index (TRON fullnode/solidity, …). */
export function normalizeDiskLayoutRoles(
  layout: MultiDiskLayoutPlan | null | undefined,
  catalog: DiskRoleDef[],
  network?: string,
  env?: string,
): MultiDiskLayoutPlan | null {
  if (!layout || catalog.length === 0) return layout ?? null
  const placements = rolePlacements(layout)
  if (placements.length === 0) return layout

  const valid = catalogIds(catalog)
  if (placements.every((p) => valid.has(p.id))) {
    return relabelRoles(layout, catalog, network, env)
  }

  const ordered: typeof placements = []
  const seen = new Set<string>()
  const add = (id: string) => {
    if (seen.has(id)) return
    const p = placements.find((x) => x.id === id)
    if (!p?.mount && !p?.dir) return
    seen.add(id)
    ordered.push(p)
  }
  for (const id of LEGACY_ROLE_ORDER) add(id)
  for (const p of [...placements].sort((a, b) => a.id.localeCompare(b.id))) {
    if (!seen.has(p.id)) add(p.id)
  }

  const nextRoles = catalog.map((def, i) => {
    const src = ordered[i]
    const prev = placements.find((p) => p.id === def.id)
    const mount = src?.mount || prev?.mount || ''
    let dir = src?.dir || prev?.dir || ''
    if (!dir && mount && network && env) {
      dir = pathOnMount(mount, network, env, def.leaf || def.id)
    }
    return {
      id: def.id,
      label: def.label,
      description: def.description,
      leaf: def.leaf,
      size_hint_gib: def.size_hint_gib,
      mount,
      dir,
    }
  })

  return applyRoleCompatFields({
    ...layout,
    network: layout.network,
    env: layout.env,
    roles: nextRoles,
  })
}

function relabelRoles(
  layout: MultiDiskLayoutPlan,
  catalog: DiskRoleDef[],
  network?: string,
  env?: string,
): MultiDiskLayoutPlan {
  const byId = new Map(rolePlacements(layout).map((p) => [p.id, p]))
  const roles = catalog.map((def) => {
    const p = byId.get(def.id)
    let dir = p?.dir || ''
    const mount = p?.mount || ''
    if (!dir && mount && network && env) {
      dir = pathOnMount(mount, network, env, def.leaf || def.id)
    }
    return {
      id: def.id,
      label: def.label,
      description: def.description,
      leaf: def.leaf,
      size_hint_gib: def.size_hint_gib,
      mount: p?.mount || '',
      dir: p?.dir || '',
    }
  })
  return applyRoleCompatFields({ ...layout, roles })
}

function applyRoleCompatFields(layout: MultiDiskLayoutPlan): MultiDiskLayoutPlan {
  const next: MultiDiskLayoutPlan = {
    ...layout,
    roles_map: Object.fromEntries(
      (layout.roles || []).map((r) => [r.id, { dir: r.dir, mount: r.mount }]),
    ),
  }
  for (const r of layout.roles || []) {
    if (r.id === 'ledger') {
      next.ledger_mount = r.mount
      next.ledger_dir = r.dir
    }
    if (r.id === 'accounts') {
      next.accounts_mount = r.mount
      next.accounts_dir = r.dir
    }
    if (r.id === 'snapshots') {
      next.snapshots_mount = r.mount
      next.snapshots_dir = r.dir
    }
    if (r.id === 'state') {
      next.state_mount = r.mount
      next.state_dir = r.dir
    }
    if (r.id === 'index') {
      next.index_mount = r.mount
      next.index_dir = r.dir
    }
    if (r.id === 'fullnode' || r.id === 'blockchain' || r.id === 'chain') {
      if (!next.ledger_dir) next.ledger_dir = r.dir
      if (!next.ledger_mount) next.ledger_mount = r.mount
    }
  }
  return next
}

function pathOnMount(mount: string, network: string, env: string, leaf: string): string {
  return pathOnDataMount(mount, network, env, leaf)
}
