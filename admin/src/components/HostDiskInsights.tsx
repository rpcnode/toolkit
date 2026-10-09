import { Accordion, Alert, Badge, Code, Group, Stack, Text } from '@mantine/core'
import type { ReactNode } from 'react'
import { IconAlertTriangle, IconCheck, IconDatabase } from '@tabler/icons-react'
import type { HostDiskInfo, HostDiskInsight, HostMountInfo, HostNofileInfo } from '../api'

export function formatFdLimit(n?: number): string {
  if (n == null || !Number.isFinite(n) || n <= 0) return '—'
  const pretty = Math.round(n).toLocaleString('en-US')
  if (n >= 1048576) {
    const m = n / 1048576
    const label = Math.abs(m - Math.round(m)) < 0.05 ? `${Math.round(m)}M` : `${m.toFixed(1)}M`
    return `${pretty} (${label})`
  }
  return pretty
}

/** Always-visible server fd limit on Check ports / Install. */
export function HostNofileCard({
  nofile,
  loading,
  compact = false,
}: {
  nofile?: HostNofileInfo | null
  loading?: boolean
  compact?: boolean
}) {
  const n = nofile?.nr_open
  const need = nofile?.need ?? 1048576
  const have = n != null && n > 0

  if (compact) {
    return (
      <Group gap={6} wrap="wrap" align="center" className="host-disk-nofile-compact">
        <Text size="xs" fw={600}>
          File descriptors
        </Text>
        {loading && !have ? (
          <Text size="xs" c="dimmed">
            reading fs.nr_open…
          </Text>
        ) : have ? (
          <>
            <Code className="mono">{formatFdLimit(n)}</Code>
            <Badge size="xs" variant="light" color={nofile?.ok === false ? 'orange' : 'teal'}>
              {nofile?.ok === false ? 'below need' : 'ok'}
            </Badge>
            <Text size="xs" c="dimmed">
              need {formatFdLimit(need)}
              {nofile?.raised ? ' · raised' : ''}
            </Text>
          </>
        ) : (
          <Text size="xs" c="dimmed">
            not reported — refresh catalog
          </Text>
        )}
      </Group>
    )
  }

  return (
    <Alert
      color={have && nofile?.ok === false ? 'orange' : 'teal'}
      variant="light"
      icon={<IconDatabase size={16} />}
      title="Server file descriptor limit"
    >
      <Stack gap={4} mt={2}>
        {loading && !have ? (
          <Text size="sm">Reading fs.nr_open from the tip host…</Text>
        ) : have ? (
          <>
            <Text size="sm">
              Current on this server: <Code>{formatFdLimit(n)}</Code>
            </Text>
            <Text size="xs" c="dimmed">
              Units use LimitNOFILE={formatFdLimit(need)}. Kernel fs.nr_open must stay at or above
              that, or leaf agents exit 205/LIMITS.
              {nofile?.raised ? ' Raised on this host just now.' : ''}
            </Text>
          </>
        ) : (
          <Text size="sm" c="dimmed">
            Tip did not report fs.nr_open. Refresh catalog after updating the host agent.
          </Text>
        )}
      </Stack>
    </Alert>
  )
}

type Props = {
  network?: string
  loading?: boolean
  error?: string | null
  mounts: HostMountInfo[]
  disks?: HostDiskInfo[]
  unused?: HostDiskInfo[]
  insights?: HostDiskInsight[]
  summary?: string
}

function kindLabel(kind?: string, raid?: string): string {
  switch (kind) {
    case 'raw_nvme':
      return 'raw NVMe'
    case 'md_raid':
      return raid ? `md ${raid}` : 'md RAID'
    case 'lvm':
      return 'LVM'
    case 'hdd':
      return 'HDD'
    case 'ssd':
      return 'SSD'
    default:
      return kind || ''
  }
}

function shortDev(source?: string, diskName?: string): string {
  if (diskName) return diskName
  if (!source) return '—'
  let s = source.replace(/^\/dev\//, '')
  if (s.startsWith('mapper/')) s = s.slice('mapper/'.length)
  const part = s.match(/^(nvme\d+n\d+)p\d+$/)
  if (part) return part[1]
  return s
}

function plannedMountLabel(d: HostDiskInfo): string {
  if (d.planned_mount) return d.planned_mount
  const n = (d.name || '').replace(/n1$/, '')
  return n ? `/data/${n}` : '—'
}

type InventoryRow = {
  key: string
  mount: string
  kind: string
  free: string
  device: string
  warn?: boolean
  muted?: boolean
}

function buildInventoryRows(mounts: HostMountInfo[], unused: HostDiskInfo[]): InventoryRow[] {
  const rows: InventoryRow[] = []
  const sorted = [...mounts.filter((m) => m.target)].sort((a, b) => {
    if (a.target === '/') return 1
    if (b.target === '/') return -1
    return a.target.localeCompare(b.target)
  })
  for (const m of sorted) {
    rows.push({
      key: m.target,
      mount: m.target,
      kind: kindLabel(m.kind, m.raid_level),
      free: m.avail_human || '—',
      device: shortDev(m.source, m.disk_name),
      warn: m.kind === 'md_raid' || sourceLooksLikeMd(m.source),
      muted: m.target === '/',
    })
  }
  for (const d of unused) {
    rows.push({
      key: `unused-${d.name}`,
      mount: plannedMountLabel(d),
      kind: 'unused',
      free: d.size_human || '—',
      device: d.name,
    })
  }
  return rows
}

function compactInsightNotes(insights: HostDiskInsight[]): { level: string; text: string }[] {
  const notes: { level: string; text: string }[] = []
  const rec = insights.find((i) => i.code === 'recommend')
  if (rec) {
    const detail = rec.detail || ''
    const mount = detail.match(/on (\S+)/)?.[1]
    notes.push({
      level: 'good',
      text: mount
        ? `data → ${mount.replace(/[.,]$/, '')}`
        : detail.split('.')[0] || rec.title || '',
    })
  } else {
    const raw = insights.find((i) => i.code === 'raw_nvme')
    if (raw) {
      const detail = raw.detail || ''
      const mounts = detail.match(/^([^—]+)/)?.[1]?.trim()
      notes.push({ level: 'good', text: mounts ? `NVMe ${mounts}` : raw.title || '' })
    }
  }
  for (const w of insights.filter((i) => i.level === 'warn')) {
    const detail = w.detail || w.title || ''
    notes.push({ level: 'warn', text: detail.split('.')[0] })
  }
  if (!notes.some((n) => n.text.toLowerCase().includes('lvm'))) {
    const lvm = insights.find((i) => i.code === 'lvm_os')
    if (lvm) notes.push({ level: 'info', text: 'root is LVM — not for ledger' })
  }
  return notes.slice(0, 3)
}

function HostDiskInventoryTable({ rows }: { rows: InventoryRow[] }) {
  if (!rows.length) return null
  return (
    <div className="host-disk-inv" role="table">
      <div className="host-disk-inv__head" role="row">
        <span role="columnheader">mount</span>
        <span role="columnheader">kind</span>
        <span role="columnheader">free</span>
        <span role="columnheader">device</span>
      </div>
      {rows.map((r) => (
        <div
          key={r.key}
          className={`host-disk-inv__row mono${r.warn ? ' is-warn' : ''}${r.muted ? ' is-muted' : ''}`}
          role="row"
        >
          <span role="cell">{r.mount}</span>
          <span role="cell">{r.kind}</span>
          <span role="cell">{r.free}</span>
          <span role="cell">{r.device}</span>
        </div>
      ))}
    </div>
  )
}

function sourceLooksLikeMd(source?: string): boolean {
  const s = (source || '').toLowerCase()
  return s.includes('/dev/md') || /(^|\/)md\d/.test(s)
}

function sourceLooksLikeLvm(source?: string): boolean {
  const s = (source || '').toLowerCase()
  return s.includes('/dev/mapper/') || s.includes('/dm-')
}

/** Fallback when tip is older than insights (no `insights` on /host/disks). */
export function deriveDiskInsights(
  network: string,
  mounts: HostMountInfo[],
  unused: HostDiskInfo[] = [],
): { insights: HostDiskInsight[]; summary: string } {
  const net = (network || 'node').toLowerCase()
  const insights: HostDiskInsight[] = []
  const raw = mounts.filter(
    (m) =>
      m.target !== '/' &&
      (m.kind === 'raw_nvme' ||
        ((m.tran === 'nvme' || m.preferred) &&
          !sourceLooksLikeMd(m.source) &&
          !sourceLooksLikeLvm(m.source) &&
          m.kind !== 'md_raid' &&
          m.kind !== 'lvm')),
  )
  const raid = mounts.filter((m) => m.kind === 'md_raid' || sourceLooksLikeMd(m.source))
  const lvmRoot = mounts.filter(
    (m) => m.target === '/' && (m.kind === 'lvm' || sourceLooksLikeLvm(m.source)),
  )
  if (raw.length) {
    insights.push({
      level: 'good',
      code: 'raw_nvme',
      title: 'Raw NVMe (no md / no LVM)',
      detail: `${raw.map((m) => m.target).join(', ')} — best place for ${net} data.`,
    })
    insights.push({
      level: 'good',
      code: 'recommend',
      title: 'Recommended data mount',
      detail: `Put ${net} data on ${raw[0].target}. One raw NVMe, not md, not the LVM OS disk.`,
    })
  }
  if (raid.length) {
    insights.push({
      level: 'warn',
      code: 'md_raid',
      title: 'Software RAID (md) on a data mount',
      detail:
        `${raid.map((m) => `${m.target} (${m.source || 'md'})`).join('; ')}. ` +
        'md is a bottleneck (array ~100% util, members 50–70%). RAID0 splits writes; RAID1 writes twice. Prefer a raw NVMe.',
    })
  }
  if (lvmRoot.length) {
    insights.push({
      level: 'info',
      code: 'lvm_os',
      title: 'OS disk is LVM (Ubuntu/curtin default)',
      detail: `/ is ${lvmRoot[0].source || 'LVM'} — fine for the system. Do not put ${net} ledger on /.`,
    })
  }
  if (unused.length) {
    insights.push({
      level: 'good',
      code: 'unused_nvme',
      title: 'Empty NVMe for node data',
      detail:
        unused
          .map((d) => {
            const m = d.planned_mount || `/data/${(d.name || '').replace(/n1$/, '')}`
            return `${d.name}${d.size_human ? ` ${d.size_human}` : ''} → ${m}`
          })
          .join(', ') + `. Install formats ext4 and mounts them. ${net} uses these, not the OS disk.`,
    })
  }
  const parts: string[] = []
  if (raw.length) parts.push(`raw NVMe ${raw.map((m) => m.target).join('+')}`)
  if (raid.length) parts.push(`md RAID ${raid.map((m) => m.target).join('+')}`)
  if (lvmRoot.length) parts.push('OS LVM /')
  if (unused.length) parts.push(`unused ${unused.map((d) => d.name).join('+')}`)
  return { insights, summary: parts.join('; ') }
}

function alertColor(level?: string): string {
  if (level === 'good') return 'teal'
  if (level === 'warn') return 'orange'
  return 'gray'
}

export function HostDiskInsights({
  network,
  loading,
  error,
  mounts,
  disks = [],
  unused = [],
  insights,
  summary,
  compact = false,
}: Props & { compact?: boolean }) {
  const nvmeDisks = disks.filter((d) => (d.tran || d.name || '').toLowerCase().includes('nvme'))
  const rawDerived = deriveDiskInsights(network || '', mounts, unused)
  const tipInsights = insights && insights.length ? insights : rawDerived.insights
  const hideOSOnly =
    unused.length > 0 ||
    nvmeDisks.length >= 2 ||
    mounts.some((m) => !!m.target && m.target !== '/' && m.target.startsWith('/data/'))
  let filteredInsights = hideOSOnly
    ? tipInsights.filter((i) => i.code !== 'data_on_root' && i.code !== 'nofile')
    : tipInsights.filter((i) => i.code !== 'nofile')
  if (
    unused.length > 0 &&
    !filteredInsights.some((i) => i.code === 'unused_nvme')
  ) {
    const extra = rawDerived.insights.filter((i) => i.code === 'unused_nvme')
    filteredInsights = [...extra, ...filteredInsights]
  }
  const derived = {
    insights: filteredInsights,
    summary:
      (insights && insights.length ? summary : rawDerived.summary) ||
      summary ||
      rawDerived.summary ||
      '',
  }
  const rows = mounts.filter((m) => m.target)
  const showUnused = unused.length > 0

  if (loading && !rows.length && !derived.insights.length) {
    const body = (
      <Text size="xs" c="dimmed">
        Reading tip disk inventory…
      </Text>
    )
    if (compact) return body
    return (
      <Alert color="gray" variant="light" icon={<IconDatabase size={16} />} title="Host disks">
        {body}
      </Alert>
    )
  }

  if (error && !rows.length && !derived.insights.length) {
    const body = <Text size="xs">{error}</Text>
    if (compact) return body
    return (
      <Alert color="orange" variant="light" icon={<IconAlertTriangle size={16} />} title="Host disks">
        {body}
      </Alert>
    )
  }

  if (!rows.length && !derived.insights.length && !showUnused && !disks.length) {
    if (loading) {
      const body = (
        <Text size="xs" c="dimmed">
          Reading tip disk inventory…
        </Text>
      )
      if (compact) return body
      return (
        <Alert color="gray" variant="light" icon={<IconDatabase size={16} />} title="Host disks">
          {body}
        </Alert>
      )
    }
    const body = (
      <Text size="xs">
        {error || 'Tip returned no disks. Refresh catalog after updating the host agent.'}
      </Text>
    )
    if (compact) return body
    return (
      <Alert color="orange" variant="light" icon={<IconAlertTriangle size={16} />} title="Host disks">
        {body}
      </Alert>
    )
  }

  const inventoryRows = buildInventoryRows(rows, unused)

  if (compact) {
    const notes = compactInsightNotes(derived.insights)
    return (
      <Stack gap={6} className="host-disk-compact">
        {error && (
          <Text size="xs" c="orange">
            {error}
          </Text>
        )}
        {notes.map((n) => (
          <Group key={n.text} gap={6} wrap="nowrap" align="center">
            {n.level === 'warn' ? (
              <IconAlertTriangle
                size={12}
                color="var(--mantine-color-orange-6)"
                style={{ flexShrink: 0 }}
              />
            ) : n.level === 'good' ? (
              <IconCheck size={12} color="var(--con-accent)" style={{ flexShrink: 0 }} />
            ) : null}
            <Text
              size="xs"
              c={n.level === 'warn' ? 'orange' : n.level === 'info' ? 'dimmed' : undefined}
              className="mono"
              style={{ minWidth: 0 }}
            >
              {n.text}
            </Text>
          </Group>
        ))}
        {inventoryRows.length > 0 && (
          <Accordion
            variant="separated"
            radius={0}
            chevronPosition="left"
            classNames={{
              root: 'node-install-wizard__ports host-disk-inv-acc',
              item: 'node-install-wizard__port-item',
              control: 'node-install-wizard__port-control',
              panel: 'node-install-wizard__port-panel',
              content: 'node-install-wizard__port-content',
              chevron: 'node-install-wizard__port-chevron',
            }}
          >
            <Accordion.Item value="inv">
              <Accordion.Control>
                <Text size="xs" fw={600}>
                  Inventory · {inventoryRows.length}
                </Text>
              </Accordion.Control>
              <Accordion.Panel>
                <HostDiskInventoryTable rows={inventoryRows} />
              </Accordion.Panel>
            </Accordion.Item>
          </Accordion>
        )}
      </Stack>
    )
  }

  const inner = (
    <Stack gap="xs" mt={4}>
      <Text size="sm">
        Tip inventory (lsblk / findmnt) — RAID, LVM, and which NVMe is safe for node data. Not a
        fio benchmark.
      </Text>
      {error && (
        <Text size="xs" c="orange">
          {error}
        </Text>
      )}
      {derived.summary && (
        <Code block className="mono">
          {derived.summary}
        </Code>
      )}
      {derived.insights.map((i) => (
        <Group key={i.code || i.title} gap={6} wrap="nowrap" align="flex-start">
          {i.level === 'warn' ? (
            <IconAlertTriangle size={12} color="var(--mantine-color-orange-6)" style={{ flexShrink: 0 }} />
          ) : i.level === 'good' ? (
            <IconCheck size={12} color="var(--mantine-color-teal-6)" style={{ flexShrink: 0 }} />
          ) : (
            <IconDatabase size={12} color="var(--mantine-color-gray-6)" style={{ flexShrink: 0 }} />
          )}
          <Stack gap={0} style={{ minWidth: 0, flex: 1 }}>
            <Badge size="xs" variant="light" color={alertColor(i.level)} w="fit-content">
              {i.title}
            </Badge>
            <Text size="xs" c="dimmed" lineClamp={4}>
              {i.detail}
            </Text>
          </Stack>
        </Group>
      ))}
      {disks.length > 0 && (
        <Stack gap={3}>
          <Text size="xs" fw={600}>
            Disks ({disks.length})
          </Text>
          {disks.map((d) => (
            <Text key={d.name} size="xs" className="mono" lineClamp={1}>
              {d.name}
              {d.size_human ? ` ${d.size_human}` : ''}
              {d.tran ? ` · ${d.tran}` : ''}
              {d.mountpoint ? ` · ${d.mountpoint}` : ' · unused'}
            </Text>
          ))}
        </Stack>
      )}
      {rows.length > 0 && (
        <Stack gap={3}>
          <Text size="xs" fw={600}>
            Mounts
          </Text>
          {rows.map((m) => {
            const kind = kindLabel(m.kind, m.raid_level)
            const bits = [
              m.target,
              kind,
              m.avail_human ? `${m.avail_human} free` : '',
              m.source,
            ].filter(Boolean)
            return (
              <Text key={m.target} size="xs" className="mono" lineClamp={2}>
                {bits.join(' · ')}
                {m.auto_mount ? ' · desktop mount' : ''}
              </Text>
            )
          })}
        </Stack>
      )}
      {showUnused && (
        <Text size="xs" c="dimmed" lineClamp={2}>
          Unused: {unused.map((d) => `${d.name}${d.size_human ? ` ${d.size_human}` : ''}`).join(', ')}
        </Text>
      )}
    </Stack>
  )

  return (
    <Alert
      color="blue"
      variant="light"
      icon={<IconDatabase size={16} />}
      title="What we see on this host"
    >
      {inner}
    </Alert>
  )
}

export function HostDisksSection({
  refreshButton,
  defaultOpen = true,
  nofile,
  diskLoading,
  network,
  diskError,
  mounts,
  disks,
  unused,
  insights,
  summary,
}: {
  refreshButton: ReactNode
  defaultOpen?: boolean
  nofile?: HostNofileInfo | null
  diskLoading?: boolean
  network?: string
  diskError?: string | null
  mounts: HostMountInfo[]
  disks?: HostDiskInfo[]
  unused?: HostDiskInfo[]
  insights?: HostDiskInsight[]
  summary?: string
}) {
  return (
    <Accordion
      variant="separated"
      radius={0}
      chevronPosition="left"
      defaultValue={defaultOpen ? 'disks' : null}
      classNames={{
        root: 'node-install-wizard__ports',
        item: 'node-install-wizard__port-item',
        control: 'node-install-wizard__port-control',
        panel: 'node-install-wizard__port-panel',
        content: 'node-install-wizard__port-content',
        chevron: 'node-install-wizard__port-chevron',
      }}
    >
      <Accordion.Item value="disks">
        <Accordion.Control>
          <Group justify="space-between" wrap="nowrap" w="100%" pr={4} gap="sm">
            <Text size="sm" fw={600} lineClamp={1} style={{ flex: 1, minWidth: 0 }}>
              Disks
            </Text>
            <div
              onClick={(e) => e.stopPropagation()}
              onKeyDown={(e) => e.stopPropagation()}
              role="presentation"
            >
              {refreshButton}
            </div>
          </Group>
        </Accordion.Control>
        <Accordion.Panel>
          <Stack gap="sm">
            <HostNofileCard nofile={nofile} loading={diskLoading} compact />
            <HostDiskInsights
              network={network}
              loading={diskLoading}
              error={diskError}
              mounts={mounts}
              disks={disks}
              unused={unused}
              insights={insights}
              summary={summary}
              compact
            />
          </Stack>
        </Accordion.Panel>
      </Accordion.Item>
    </Accordion>
  )
}
