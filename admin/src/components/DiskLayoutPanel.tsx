import { useEffect, type ReactNode } from 'react'
import { Alert, Badge, Code, Group, Select, Stack, Text } from '@mantine/core'
import { IconDatabase } from '@tabler/icons-react'
import type {
  DiskRoleDef,
  HostDiskInfo,
  HostMountInfo,
  MultiDiskLayoutPlan,
  SnapshotSizeHint,
} from '../api'
import { pathOnDataMount } from '../lib/networkPaths'

type Props = {
  network: string
  env: string
  loading?: boolean
  error?: string | null
  mounts: HostMountInfo[]
  disks?: HostDiskInfo[]
  unused?: HostDiskInfo[]
  roles: DiskRoleDef[]
  recommended: MultiDiskLayoutPlan | null
  layout: MultiDiskLayoutPlan | null
  rules?: string[]
  /** Live-probed snapshot archive size — the catalog size hint below can be stale. */
  snapshotHint?: SnapshotSizeHint | null
  /** null — nothing sent yet; true/false — last save to the node's panel row. */
  saved?: boolean | null
  onChange: (next: MultiDiskLayoutPlan) => void
  onUseRecommended: () => void
  /** Inside Host disks accordion — no outer Alert card. */
  embedded?: boolean
}

function formatGiB(giB: number | undefined): string {
  if (!giB || giB <= 0) return ''
  if (giB >= 1024) return `~${(giB / 1024).toFixed(giB >= 10240 ? 0 : 1)} TB`
  return `~${Math.round(giB)} GB`
}

function plannedMountOf(d: HostDiskInfo): string {
  if (d.planned_mount) return d.planned_mount
  const n = (d.name || '').replace(/n1$/, '')
  return n ? `/data/${n}` : ''
}

function mountLabel(m: HostMountInfo): string {
  const kind =
    m.kind === 'raw_nvme'
      ? 'raw NVMe'
      : m.kind === 'md_raid'
        ? m.raid_level
          ? `md ${m.raid_level}`
          : 'md RAID'
        : m.kind === 'lvm'
          ? 'LVM'
          : m.tran
            ? m.tran.toUpperCase()
            : m.preferred
              ? 'SSD'
              : m.rota
                ? 'HDD'
                : undefined
  const bits = [
    m.target,
    kind,
    m.avail_human ? `${m.avail_human} free` : undefined,
    m.model,
    // Files stay, we only add a subfolder — but the path is gone after a reboot
    // until this filesystem is in /etc/fstab.
    m.auto_mount ? 'desktop mount, not mounted at boot' : undefined,
  ].filter(Boolean)
  return bits.join(' · ')
}

/** Wizard accordion title — not the internal (JBOD) suffix. */
export function diskLayoutTitleFor(network: string): string {
  const n = (network || '').toLowerCase()
  if (n === 'solana') return 'Solana disk layout'
  if (n === 'tron') return 'TRON disk layout'
  if (n === 'aptos') return 'Aptos disk layout'
  if (n === 'sui') return 'Sui disk layout'
  if (n === 'polygon' || n === 'matic') return 'Polygon disk layout'
  const label = n ? n.charAt(0).toUpperCase() + n.slice(1) : 'Node'
  return `${label} disk layout`
}

/** Expected role names when the catalog has not loaded yet. */
export function expectedDiskRolesHint(network: string): string {
  const n = (network || '').toLowerCase()
  if (n === 'solana') return 'ledger / accounts'
  if (n === 'tron') return 'FullNode / Solidity'
  if (n === 'polygon' || n === 'matic') return 'Bor / Heimdall'
  if (n === 'ethereum' || n === 'base' || n === 'optimism' || n === 'arb') {
    return 'execution / consensus'
  }
  if (n === 'bsc') return 'geth / beacon'
  if (n === 'sui') return 'state / rocksdb'
  return "this network's roles"
}

function mountOptionShort(m: HostMountInfo): string {
  const kind =
    m.kind === 'raw_nvme'
      ? 'NVMe'
      : m.kind === 'md_raid'
        ? 'md RAID'
        : m.kind === 'lvm'
          ? 'LVM'
          : m.tran
            ? m.tran.toUpperCase()
            : m.preferred
              ? 'SSD'
              : m.rota
                ? 'HDD'
                : 'disk'
  const free = m.avail_human ? `${m.avail_human} free` : ''
  return [m.target, free, kind].filter(Boolean).join(' · ')
}

function layoutIntro(network: string): string {
  switch ((network || '').toLowerCase()) {
    case 'tron':
      return 'Choose which NVMe mount holds each TRON data role. FullNode DB is the java-tron ledger; put it on a raw NVMe — not / or md RAID.'
    case 'solana':
      return 'Choose which NVMe mount holds ledger, accounts, and snapshot archives. Separate mounts when you have multiple NVMe.'
    default:
      return 'Choose which data mount on this host holds each role. The OS disk (/) is not offered.'
  }
}

function pathOnMount(mount: string, network: string, env: string, leaf: string): string {
  return pathOnDataMount(mount, network, env, leaf)
}

/**
 * Snapshot size line — the live HEAD-probed archive size next to the static
 * catalog hint the role split above is built from. The catalog number can go
 * stale for months while a chain's archive keeps growing (TRON mainnet grew
 * past 3x its old DiskHintGiB unnoticed), so show both instead of only the
 * role split.
 */
function snapshotHintText(hint: SnapshotSizeHint | null | undefined): string {
  if (!hint) return ''
  const live = formatGiB((hint.archive_bytes || 0) / 1024 ** 3) || hint.archive_human || ''
  const catalogGiB = formatGiB(hint.catalog_gib)
  if (live) {
    const cached = hint.source === 'content-length-cache' ? ' (cached ≤30 min)' : ' (just probed)'
    return `Snapshot archive: ${live}${cached}${
      catalogGiB && catalogGiB !== live ? ` · catalog hint ${catalogGiB}` : ''
    } — pick disks that comfortably exceed this.`
  }
  if (catalogGiB) {
    return `Snapshot archive size not probed (mirror unreachable) — catalog hint ${catalogGiB}. Plan generously; the real archive may be larger.`
  }
  return ''
}

function snapshotHintShort(hint: SnapshotSizeHint | null | undefined): string {
  if (!hint) return ''
  const live = formatGiB((hint.archive_bytes || 0) / 1024 ** 3) || hint.archive_human || ''
  const catalogGiB = formatGiB(hint.catalog_gib)
  if (live) {
    const tag = hint.source === 'content-length-cache' ? 'cached' : 'probed'
    return catalogGiB && catalogGiB !== live
      ? `snapshot ~${live} (${tag}) · catalog ${catalogGiB}`
      : `snapshot ~${live} (${tag})`
  }
  if (catalogGiB) return `snapshot catalog ${catalogGiB}`
  return ''
}

function titleFor(network: string): string {
  return `${diskLayoutTitleFor(network)} (JBOD)`
}

type MountOption = { value: string; label: string }

/** Mantine Select rejects duplicate `value` — tip can list the same mount twice (md RAID members). */
function uniqueMountOptions(items: MountOption[]): MountOption[] {
  const seen = new Set<string>()
  const out: MountOption[] = []
  for (const o of items) {
    if (!o.value || seen.has(o.value)) continue
    seen.add(o.value)
    out.push(o)
  }
  return out
}

export function DiskLayoutPanel({
  network,
  env,
  loading,
  error,
  mounts,
  disks = [],
  unused = [],
  roles,
  recommended,
  layout,
  rules,
  snapshotHint,
  saved,
  onChange,
  onUseRecommended,
  embedded = false,
}: Props) {
  const rootDisk = mounts.find((m) => m.target === '/')?.disk_name
  const mountOptions: MountOption[] = mounts
    .filter((m) => {
      if (!m.target || m.target === '/') return false
      if (m.target === '/data' && rootDisk && m.disk_name === rootDisk) return false
      return true
    })
    .map((m) => ({
      value: m.target,
      label: embedded ? mountOptionShort(m) : mountLabel(m),
    }))
  // Any disk that backs a mount already holds somebody's data — offering it as
  // «format on Install» is how a data disk gets wiped.
  const mountedDisks = new Set(
    mounts.map((m) => m.disk_name).filter(Boolean) as string[],
  )
  const extraUnused = unused.length
    ? unused
    : disks.filter((d) => {
        const n = (d.name || '').toLowerCase()
        if (!n.includes('nvme')) return false
        if (rootDisk && d.name === rootDisk) return false
        if (mountedDisks.has(d.name)) return false
        if ((d.fstype || '').trim()) return false
        const mp = d.mountpoint || ''
        if (mp && mp !== '/' && !mp.startsWith('/boot')) return false
        return true
      })
  for (const d of extraUnused) {
    const target = plannedMountOf(d)
    if (!target || mountOptions.some((o) => o.value === target)) continue
    const size = d.size_human ? ` ${d.size_human}` : ''
    mountOptions.push({
      value: target,
      label: embedded
        ? `${target} · empty · format on Install${size}`
        : `${target} · empty NVMe${size} · format on Install`,
    })
  }
  const dataMount = mounts.find((m) => m.target === '/data' && m.kind !== 'lvm')
  if (
    dataMount &&
    !mountOptions.some((o) => o.value === '/data') &&
    dataMount.disk_name &&
    dataMount.disk_name !== rootDisk
  ) {
    mountOptions.unshift({
      value: '/data',
      label: embedded ? mountOptionShort(dataMount) : mountLabel(dataMount),
    })
  }
  const options = uniqueMountOptions(mountOptions)

  const roleDefs =
    roles.length > 0
      ? roles
      : (layout?.roles || recommended?.roles || []).length
        ? (layout?.roles || recommended?.roles || []).map((r) => ({
            id: r.id,
            label: r.label || r.id,
            description: r.description,
            leaf: r.leaf || r.id,
            size_hint_gib: r.size_hint_gib,
          }))
        : []

  // Available free space per pickable mount — empty NVMe counts its full
  // size (it will be formatted), so a role's size hint can be checked
  // against it before Install, not just after ENOSPC on the node.
  const availByMount = new Map<string, number>()
  for (const m of mounts) {
    if (m.target && typeof m.avail_bytes === 'number') availByMount.set(m.target, m.avail_bytes)
  }
  for (const d of extraUnused) {
    const target = plannedMountOf(d)
    if (!target || availByMount.has(target)) continue
    const bytes = d.fsavail_bytes ?? d.size_bytes
    if (typeof bytes === 'number') availByMount.set(target, bytes)
  }

  function rolePlacement(id: string) {
    return (layout?.roles || []).find((r) => r.id === id) ||
      (recommended?.roles || []).find((r) => r.id === id)
  }

  function setMount(roleId: string, mount: string | null) {
    if (!mount) return
    const defs = roleDefs.length ? roleDefs : []
    if (defs.length === 0) return
    const nextRoles = defs.map((d) => {
      const prev = rolePlacement(d.id)
      const useMount = d.id === roleId ? mount : prev?.mount || options[0]?.value || ''
      const leaf = d.leaf || d.id
      return {
        id: d.id,
        label: d.label,
        description: d.description,
        leaf,
        mount: useMount,
        dir: pathOnMount(useMount, network, env, leaf),
        size_hint_gib: d.size_hint_gib,
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
      if (r.id === 'chain' || r.id === 'execution' || r.id === 'chaindata' || r.id === 'fullnode' || r.id === 'blockchain' || r.id === 'ledger') {
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

  useEffect(() => {
    if (loading || layout || recommended || !options.length || roleDefs.length === 0) return
    const defs = roleDefs
    const nextRoles = defs.map((d, i) => {
      const mount = options[Math.min(i, options.length - 1)]?.value || ''
      const leaf = d.leaf || d.id
      return {
        id: d.id,
        label: d.label,
        description: d.description,
        leaf,
        mount,
        dir: pathOnMount(mount, network, env, leaf),
        size_hint_gib: d.size_hint_gib,
      }
    })
    if (!nextRoles.some((r) => r.mount && r.mount !== '/' && r.mount !== '/data')) return
    const distinct = new Set(nextRoles.map((r) => r.mount).filter(Boolean))
    onChange({
      strategy: distinct.size >= 3 ? 'jbod_3' : distinct.size === 2 ? 'jbod_2' : 'single',
      network,
      env,
      roles: nextRoles,
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, layout, recommended, options.length, roleDefs.length])

  const codeLines = (layout?.roles || recommended?.roles || []).map((r) => {
    const hint = formatGiB(r.size_hint_gib)
    return `${(r.label || r.id).padEnd(22)} ${(r.dir || '—').padEnd(46)}${hint}`
  })

  const body = (
    <Stack
      gap={embedded ? 'sm' : 'sm'}
      mt={embedded ? 0 : 4}
      className={embedded ? 'disk-layout-panel disk-layout-panel--embedded' : undefined}
    >
      {!embedded && (
        <Text size="sm" c="dimmed">
          Pick a data NVMe for each role. /data on the OS disk is not offered. Empty NVMe are
          formatted and mounted on Install.
        </Text>
      )}
      {embedded && (
        <Stack gap={6}>
          <Text size="xs" c="dimmed">
            {layoutIntro(network)}
          </Text>
          {!!snapshotHintShort(snapshotHint) && (
            <Text size="xs" c="dimmed" className="mono">
              {snapshotHintShort(snapshotHint)}
            </Text>
          )}
        </Stack>
      )}
        {!loading && options.length === 0 && (
          <Text size="xs" c="orange">
            No data NVMe in the picker. Format/mount empty disks under /data/nvmeN, then Refresh
            catalog. /data on the OS disk is not offered.
          </Text>
        )}
        {loading && (
          <Text size="xs" c="dimmed">
            Loading tip disk inventory…
          </Text>
        )}
        {error && (
          <Text size="xs" c="orange">
            {error} — Install stays blocked until a data NVMe is visible. Do not use the OS disk.
          </Text>
        )}
        {!embedded && !!rules?.length && (
          <Text size="xs" c="dimmed">
            {rules.slice(0, 2).join(' · ')}
          </Text>
        )}
        {!embedded && !!snapshotHintText(snapshotHint) && (
          <Text size="xs" c={snapshotHint?.archive_human || snapshotHint?.archive_bytes ? 'cyan' : 'orange'}>
            {snapshotHintText(snapshotHint)}
          </Text>
        )}
        {!loading && roleDefs.length === 0 && (
          <Text size="xs" c="orange">
            Disk roles not loaded from the panel catalog — Refresh catalog (or restart the panel
            after updating network.yml). Do not Install until {expectedDiskRolesHint(network)}{' '}
            appear.
          </Text>
        )}
        {roleDefs.map((d) => {
          const cur = rolePlacement(d.id)
          const mountVal = cur?.mount || options[0]?.value || ''
          const sizeHint = formatGiB(d.size_hint_gib)
          const dirPreview =
            cur?.dir ||
            (mountVal
              ? pathOnMount(mountVal, network, env, d.leaf || d.id)
              : '')
          const description = embedded
            ? undefined
            : [d.description || d.id, sizeHint && `${sizeHint} expected`].filter(Boolean).join(' · ')
          const availBytes = availByMount.get(mountVal)
          const needBytes = (d.size_hint_gib || 0) * 1024 ** 3
          const tight = needBytes > 0 && typeof availBytes === 'number' && availBytes < needBytes
          return (
            <div key={d.id} className={embedded ? 'disk-layout-panel__role' : undefined}>
              {embedded && (
                <Stack gap={4} className="disk-layout-panel__role-head">
                  <Group justify="space-between" wrap="wrap" gap={6} align="flex-start">
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <Text size="sm" fw={600}>
                        {d.label || d.id}
                      </Text>
                      {d.description ? (
                        <Text size="xs" c="dimmed">
                          {d.description}
                        </Text>
                      ) : null}
                    </div>
                    {sizeHint ? (
                      <Badge size="xs" variant="light" color="gray">
                        {sizeHint} expected
                      </Badge>
                    ) : null}
                  </Group>
                </Stack>
              )}
              <Select
                label={embedded ? 'Mount on host' : d.label}
                description={description}
                size={embedded ? 'xs' : 'sm'}
                placeholder={embedded ? 'Choose NVMe mount…' : undefined}
                data={options}
                value={mountVal}
                onChange={(v) => setMount(d.id, v)}
                searchable
                allowDeselect={false}
                error={
                  tight
                    ? `${sizeHint} expected, but this disk only has ${formatGiB((availBytes || 0) / 1024 ** 3)} free`
                    : undefined
                }
              />
              {embedded && dirPreview ? (
                <Text size="xs" c="dimmed" className="mono disk-layout-panel__path">
                  → {dirPreview}
                </Text>
              ) : null}
            </div>
          )
        })}
        {!embedded && (
          <Code block className="mono">
            {[
              ...codeLines,
              `strategy: ${layout?.strategy || recommended?.strategy || '—'}`,
            ].join('\n')}
          </Code>
        )}
        <Group justify="space-between" wrap="wrap" gap={6}>
          <Text size="xs" c={saved === false ? 'orange' : 'dimmed'}>
            {embedded
              ? saved === false
                ? 'not saved'
                : saved
                  ? 'saved'
                  : `tip: ${recommended?.strategy || '—'}`
              : saved === null || saved === undefined
                ? `Tip recommended: ${recommended?.strategy || '—'}`
                : saved
                  ? 'Saved to the node — kept on reload and retry'
                  : 'Not saved to the node — the choice may be lost on reload'}
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
  )

  if (embedded) {
    return body
  }

  return (
    <Alert
      color="grape"
      variant="light"
      icon={<IconDatabase size={16} />}
      title={titleFor(network)}
    >
      {body}
    </Alert>
  )
}

/** Wizard step — disk role picker, always visible (no accordion). */
export function DiskLayoutSection({
  title,
  children,
}: {
  title: string
  children: ReactNode
}) {
  return (
    <section className="disk-layout-section" aria-label={title}>
      <div className="disk-layout-section__head">
        <Text size="sm" fw={600} lineClamp={1}>
          {title}
        </Text>
      </div>
      <div className="disk-layout-section__body">{children}</div>
    </section>
  )
}
