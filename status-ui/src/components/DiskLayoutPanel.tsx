import { Alert, Code, Group, Select, Stack, Text } from '@mantine/core'
import { IconDatabase } from '@tabler/icons-react'
import type { DiskRoleDef, HostMountInfo, MultiDiskLayoutPlan } from '../api'

type Props = {
  network: string
  env: string
  loading?: boolean
  error?: string | null
  mounts: HostMountInfo[]
  roles: DiskRoleDef[]
  recommended: MultiDiskLayoutPlan | null
  layout: MultiDiskLayoutPlan | null
  rules?: string[]
  onChange: (next: MultiDiskLayoutPlan) => void
  onUseRecommended: () => void
}

function mountLabel(m: HostMountInfo): string {
  const bits = [
    m.target,
    m.tran ? m.tran.toUpperCase() : m.preferred ? 'SSD' : m.rota ? 'HDD' : undefined,
    m.avail_human ? `${m.avail_human} free` : undefined,
    m.model,
  ].filter(Boolean)
  return bits.join(' · ')
}

function pathOnMount(mount: string, network: string, env: string, leaf: string): string {
  const m = (mount || '').trim()
  const leafClean = (leaf || '').replace(/^\/+|\/+$/g, '')
  let base = ''
  if (!m || m === '/' || m === '/data') {
    base = `/data/${network}/${env}`
  } else {
    base = `${m.replace(/\/$/, '')}/${network}/${env}`
  }
  return leafClean ? `${base}/${leafClean}` : base
}

function titleFor(network: string): string {
  const n = (network || '').toLowerCase()
  if (n === 'solana') return 'Solana disk layout (JBOD)'
  return `${network || 'Node'} disk layout (JBOD)`
}

export function DiskLayoutPanel({
  network,
  env,
  loading,
  error,
  mounts,
  roles,
  recommended,
  layout,
  rules,
  onChange,
  onUseRecommended,
}: Props) {
  const options = mounts.map((m) => ({
    value: m.target,
    label: mountLabel(m),
  }))
  if (!options.some((o) => o.value === '/data')) {
    options.unshift({ value: '/data', label: '/data · default' })
  }

  const roleDefs =
    roles.length > 0
      ? roles
      : (layout?.roles || recommended?.roles || []).map((r) => ({
          id: r.id,
          label: r.label || r.id,
          description: r.description,
          leaf: r.leaf || r.id,
        }))

  function rolePlacement(id: string) {
    return (layout?.roles || []).find((r) => r.id === id) ||
      (recommended?.roles || []).find((r) => r.id === id)
  }

  function setMount(roleId: string, mount: string | null) {
    if (!mount) return
    const defs = roleDefs.length
      ? roleDefs
      : multiFallbackRoles(network)
    const nextRoles = defs.map((d) => {
      const prev = rolePlacement(d.id)
      const useMount = d.id === roleId ? mount : prev?.mount || '/data'
      const leaf = d.leaf || d.id
      return {
        id: d.id,
        label: d.label,
        description: d.description,
        leaf,
        mount: useMount,
        dir: pathOnMount(useMount, network, env, leaf),
      }
    })
    const distinct = new Set(nextRoles.map((r) => r.mount).filter(Boolean))
    const strategy =
      distinct.size >= 3 ? 'jbod_3' : distinct.size === 2 ? 'jbod_2' : 'single'
    const next: MultiDiskLayoutPlan = {
      ...(layout || recommended || {}),
      strategy,
      network,
      env,
      roles: nextRoles,
    }
    // Solana / aptos compat flat fields for provision payload.
    for (const r of nextRoles) {
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
      if (r.id === 'chain' || r.id === 'execution' || r.id === 'chaindata' || r.id === 'fullnode' || r.id === 'blockchain') {
        next.ledger_mount = r.mount
        next.ledger_dir = r.dir
      }
      if (r.id === 'consensus' || r.id === 'solidity' || r.id === 'archive') {
        next.accounts_mount = r.mount
        next.accounts_dir = r.dir
      }
    }
    next.roles_map = Object.fromEntries(
      nextRoles.map((r) => [r.id, { dir: r.dir, mount: r.mount }]),
    )
    onChange(next)
  }

  const codeLines = (layout?.roles || recommended?.roles || []).map(
    (r) => `${(r.label || r.id).padEnd(22)} ${r.dir || '—'}`,
  )

  return (
    <Alert
      color="grape"
      variant="light"
      icon={<IconDatabase size={16} />}
      title={titleFor(network)}
    >
      <Stack gap="sm" mt={4}>
        <Text size="sm" c="dimmed">
          Prefer separate NVMe JBOD for data roles (not one RAID). Small OS/root SSD is excluded from
          recommendations when larger data mounts exist.
        </Text>
        {loading && (
          <Text size="xs" c="dimmed">
            Loading tip disk inventory…
          </Text>
        )}
        {error && (
          <Text size="xs" c="orange">
            {error} — default /data layout will be used if you Install now.
          </Text>
        )}
        {!!rules?.length && (
          <Text size="xs" c="dimmed">
            {rules.slice(0, 2).join(' · ')}
          </Text>
        )}
        {roleDefs.map((d) => {
          const cur = rolePlacement(d.id)
          return (
            <Select
              key={d.id}
              label={d.label}
              description={d.description || d.id}
              data={options}
              value={cur?.mount || '/data'}
              onChange={(v) => setMount(d.id, v)}
              searchable
              allowDeselect={false}
            />
          )
        })}
        <Code block className="mono">
          {[
            ...codeLines,
            `strategy: ${layout?.strategy || recommended?.strategy || '—'}`,
          ].join('\n')}
        </Code>
        <Group justify="space-between">
          <Text size="xs" c="dimmed">
            Tip recommended: {recommended?.strategy || '—'}
          </Text>
          <Text
            size="xs"
            c="cyan"
            style={{ cursor: 'pointer', textDecoration: 'underline' }}
            onClick={onUseRecommended}
          >
            Reset to recommended
          </Text>
        </Group>
      </Stack>
    </Alert>
  )
}

function multiFallbackRoles(network: string): DiskRoleDef[] {
  const n = (network || '').toLowerCase()
  if (n === 'solana') {
    return [
      { id: 'ledger', label: 'Ledger', leaf: 'ledger', description: 'Agave --ledger' },
      { id: 'accounts', label: 'Accounts', leaf: 'accounts', description: 'Agave --accounts' },
      { id: 'snapshots', label: 'Snapshots', leaf: 'snapshots', description: 'Agave --snapshots' },
    ]
  }
  return [
    { id: 'primary', label: 'Primary DB', leaf: 'db' },
    { id: 'secondary', label: 'Secondary / aux', leaf: 'aux' },
  ]
}
