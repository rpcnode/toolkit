import { Alert, Badge, Code, Group, Stack, Text } from '@mantine/core'
import { IconAlertTriangle, IconCheck, IconDatabase } from '@tabler/icons-react'
import type { HostDiskInfo, HostDiskInsight, HostMountInfo } from '../api'

type Props = {
  network?: string
  loading?: boolean
  error?: string | null
  mounts: HostMountInfo[]
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

function kindColor(kind?: string): string {
  switch (kind) {
    case 'raw_nvme':
      return 'teal'
    case 'md_raid':
      return 'orange'
    case 'lvm':
      return 'gray'
    case 'hdd':
      return 'yellow'
    default:
      return 'dark'
  }
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
      level: 'info',
      code: 'unused_nvme',
      title: 'Unused NVMe (no filesystem)',
      detail: unused.map((d) => `${d.name}${d.size_human ? ` ${d.size_human}` : ''}`).join(', '),
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
  unused = [],
  insights,
  summary,
}: Props) {
  const derived =
    insights && insights.length
      ? { insights, summary: summary || '' }
      : deriveDiskInsights(network || '', mounts, unused)
  const rows = mounts.filter((m) => m.target)
  const showUnused = unused.length > 0

  if (loading && !rows.length && !derived.insights.length) {
    return (
      <Alert color="gray" variant="light" icon={<IconDatabase size={16} />} title="Host disks">
        <Text size="xs" c="dimmed">
          Reading tip disk inventory…
        </Text>
      </Alert>
    )
  }

  if (error && !rows.length && !derived.insights.length) {
    return (
      <Alert color="orange" variant="light" icon={<IconAlertTriangle size={16} />} title="Host disks">
        <Text size="xs">{error}</Text>
      </Alert>
    )
  }

  if (!rows.length && !derived.insights.length && !showUnused) {
    return null
  }

  return (
    <Alert
      color="blue"
      variant="light"
      icon={<IconDatabase size={16} />}
      title="What we see on this host"
    >
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
          <Stack key={i.code || i.title} gap={2}>
            <Group gap={6} wrap="nowrap">
              {i.level === 'warn' ? (
                <IconAlertTriangle size={14} color="var(--mantine-color-orange-6)" />
              ) : i.level === 'good' ? (
                <IconCheck size={14} color="var(--mantine-color-teal-6)" />
              ) : (
                <IconDatabase size={14} color="var(--mantine-color-gray-6)" />
              )}
              <Badge size="xs" variant="light" color={alertColor(i.level)}>
                {i.title}
              </Badge>
            </Group>
            <Text size="xs">{i.detail}</Text>
          </Stack>
        ))}
        {rows.length > 0 && (
          <Stack gap={4}>
            <Text size="xs" fw={600}>
              Mounts
            </Text>
            {rows.map((m) => {
              const kind = kindLabel(m.kind, m.raid_level)
              return (
                <Group key={m.target} gap={6} wrap="wrap">
                  <Text size="xs" className="mono">
                    {m.target}
                  </Text>
                  {kind && (
                    <Badge size="xs" variant="light" color={kindColor(m.kind)}>
                      {kind}
                    </Badge>
                  )}
                  {m.avail_human && (
                    <Text size="xs" c="dimmed">
                      {m.avail_human} free
                    </Text>
                  )}
                  {m.source && (
                    <Text size="xs" c="dimmed">
                      {m.source}
                    </Text>
                  )}
                </Group>
              )
            })}
          </Stack>
        )}
        {showUnused && (
          <Text size="xs" c="dimmed">
            Unused: {unused.map((d) => `${d.name}${d.size_human ? ` ${d.size_human}` : ''}`).join(', ')}
          </Text>
        )}
      </Stack>
    </Alert>
  )
}
