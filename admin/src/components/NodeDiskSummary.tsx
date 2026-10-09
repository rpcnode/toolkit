import { Text } from '@mantine/core'
import type { MultiDiskLayoutPlan, ProvisionDiskLayout } from '../api'

type Props = {
  layout?: ProvisionDiskLayout | MultiDiskLayoutPlan | null
  /** Inline in a header subtitle (no wrapper margins). */
  inline?: boolean
  /** One `role → mount` row per placement, for the narrow aside column. */
  rows?: boolean
}

type Placement = { id: string; label: string; dir: string; mount: string }

const ROLE_LABEL: Record<string, string> = {
  ledger: 'ledger',
  accounts: 'accounts',
  snapshots: 'snapshots',
  state: 'state',
  index: 'index',
  chain: 'chain',
  chaindata: 'chaindata',
  execution: 'execution',
  consensus: 'consensus',
  fullnode: 'fullnode',
  blockchain: 'blockchain',
  archive: 'archive',
  solidity: 'solidity',
}

/** placements flattens both shapes the panel stores: roles[] and roles map. */
export function diskPlacements(
  layout?: ProvisionDiskLayout | MultiDiskLayoutPlan | null,
): Placement[] {
  if (!layout) return []
  const out: Placement[] = []
  const push = (id: string, dir?: string, mount?: string, label?: string) => {
    const d = (dir || '').trim()
    const m = (mount || '').trim()
    if (!d && !m) return
    if (out.some((p) => p.id === id)) return
    out.push({ id, label: label || ROLE_LABEL[id] || id, dir: d, mount: m })
  }
  const roles = (layout as MultiDiskLayoutPlan).roles
  if (Array.isArray(roles)) {
    for (const r of roles) if (r?.id) push(r.id, r.dir, r.mount, r.label)
  } else if (roles && typeof roles === 'object') {
    for (const [id, v] of Object.entries(roles as Record<string, { dir?: string; mount?: string }>)) {
      push(id, v?.dir, v?.mount)
    }
  }
  const flat = layout as MultiDiskLayoutPlan
  push('ledger', flat.ledger_dir, flat.ledger_mount)
  push('accounts', flat.accounts_dir, flat.accounts_mount)
  push('snapshots', flat.snapshots_dir, flat.snapshots_mount)
  push('state', flat.state_dir, flat.state_mount)
  push('index', flat.index_dir, flat.index_mount)
  return out
}

/**
 * NodeDiskSummary — where this node's data actually lives.
 *
 * The operator picks disks once in the wizard and then has no way to see the
 * answer without opening Install again; on a JBOD host that is the first thing
 * they need when a role runs out of space.
 */
export function NodeDiskSummary({ layout, inline, rows }: Props) {
  const places = diskPlacements(layout)
  if (places.length === 0) return null
  const full = places.map((p) => `${p.label} → ${p.dir || p.mount}`).join('\n')
  if (rows) {
    return (
      <div className="node-meta__group">
        {places.map((p) => (
          <div className="node-meta__row" key={p.id} title={p.dir || p.mount}>
            <span className="node-meta__label">{p.label}</span>
            <span className="node-meta__value">
              <Text span size="xs" className="mono" c="gray.4">
                {p.mount || p.dir}
              </Text>
            </span>
          </div>
        ))}
      </div>
    )
  }
  return (
    <Text size="xs" c="dimmed" mt={inline ? 0 : 4} title={full}>
      disks{' '}
      {places.map((p, i) => (
        <Text span key={p.id} c="dimmed">
          {i > 0 ? ' · ' : ''}
          {p.label}{' '}
          <Text span className="mono" c="gray.4">
            {p.mount || p.dir}
          </Text>
        </Text>
      ))}
    </Text>
  )
}
