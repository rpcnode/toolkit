import { Alert, Badge, Button, Card, Group, Modal, Select, SimpleGrid, Stack, Text } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconPlus, IconTrash } from '@tabler/icons-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, type NetworkCatalogItem, type NetworkEnvDetail } from '../api'
import { AppChrome, PageHint } from '../components/AppChrome'
import { NetworkIcon } from '../components/NetworkIcon'
import { loadNetworksCatalog } from '../lib/networksCatalog'
import { blockProps } from '../lib/blockId'

function fmtGiB(n?: number): string {
  if (n == null || !(n > 0)) return '—'
  if (n >= 1024) {
    const t = n / 1024
    return `${t >= 10 ? Math.round(t) : t.toFixed(1)} TiB`
  }
  return `${Math.round(n)} GiB`
}

function snapshotLabel(e: NetworkEnvDetail): string {
  const s = (e.snapshot || '').toLowerCase()
  if (s === 'required') return 'required'
  if (s === 'optional') return 'optional'
  if (s === 'never' || !s) return '—'
  return s
}

/** Env label: drop network name from DisplayName ("Solana Mainnet" → "Mainnet"). */
function envShortLabel(network: NetworkCatalogItem, env: NetworkEnvDetail): string {
  const id = (env.id || '').trim()
  const label = (env.label || '').trim()
  if (!label) return id || '—'
  const prefixes = [network.label, network.id]
    .map((s) => (s || '').trim())
    .filter(Boolean)
    .sort((a, b) => b.length - a.length)
  for (const p of prefixes) {
    const re = new RegExp(`^${p.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s+`, 'i')
    if (re.test(label)) {
      const rest = label.replace(re, '').trim()
      // "Arbitrum One Mainnet" → "One Mainnet" — prefer catalog id
      if (!rest || /\s/.test(rest)) return id || rest || label
      return rest
    }
  }
  if (prefixes.some((p) => label.toLowerCase().includes(p.toLowerCase()))) {
    return id || label
  }
  return label
}

function mediaLabel(m?: string): string {
  switch ((m || '').toLowerCase()) {
    case 'nvme':
      return 'NVMe'
    case 'ssd':
      return 'SSD'
    case 'hdd':
      return 'HDD'
    default:
      return m || '—'
  }
}

function mediaColor(m?: string): string {
  switch ((m || '').toLowerCase()) {
    case 'nvme':
      return 'violet'
    case 'ssd':
      return 'teal'
    case 'hdd':
      return 'gray'
    default:
      return 'gray'
  }
}

/**
 * Disk media badges for one env — from `disk_roles` (per-role media, JBOD) or the network-wide
 * `disk_media` fallback. Same source as the network card badges used to show once per card;
 * repeated on every env row so it's clear which disk to provision for *that* env/network pair.
 */
function DiskMediaBadges({ network }: { network: NetworkCatalogItem }) {
  const roles = network.disk_roles || []
  if (roles.length > 0) {
    return (
      <>
        {roles.map((r) => (
          <Badge key={r.id} size="xs" variant="light" color={mediaColor(r.media)} tt="none" title={r.label}>
            {mediaLabel(r.media)}
          </Badge>
        ))}
      </>
    )
  }
  return (
    <Badge size="xs" variant="light" color={mediaColor(network.disk_media)} tt="none">
      {mediaLabel(network.disk_media || 'ssd')}
    </Badge>
  )
}

/** Any env on this network has a size worth a Plan/Full/Archive column header. */
function hasAnySize(envs: NetworkEnvDetail[]): boolean {
  return envs.some(
    (e) => (e.disk_hint_gib ?? 0) > 0 || (e.full_node_gib ?? 0) > 0 || (e.archive_gib ?? 0) > 0,
  )
}

function envSpecLabel(e: NetworkEnvDetail): string {
  const parts: string[] = []
  if (e.cpu_cores && e.cpu_cores > 0) parts.push(`${Math.round(e.cpu_cores)} vCPU`)
  if (e.memory_gib && e.memory_gib > 0) parts.push(`${fmtGiB(e.memory_gib)} RAM`)
  return parts.join(' · ')
}

function cmpStr(a: string, b: string): number {
  return a.localeCompare(b, undefined, { sensitivity: 'base', numeric: true })
}

export function NetworksPage() {
  const [items, setItems] = useState<NetworkCatalogItem[]>([])
  const [open, setOpen] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    const res = await api.networksAll()
    setItems(res.items || [])
    await loadNetworksCatalog()
  }, [])

  useEffect(() => {
    void load().catch((e) => setError(String((e as Error).message || e)))
  }, [load])

  const added = useMemo(
    () =>
      items
        .filter((n) => n.status === 'ready')
        .sort((a, b) => cmpStr(a.label || a.id, b.label || b.id)),
    [items],
  )

  async function removeNetwork(n: NetworkCatalogItem) {
    try {
      await api.networkRemove(n.id)
      await load()
    } catch (err) {
      notifications.show({ color: 'red', message: String((err as Error).message || err) })
    }
  }

  return (
    <AppChrome
      block="networks"
      title="Networks"
      subtitle={
        <PageHint>
          Add a network from the catalog, then download its client on Clients (GitHub token in
          Settings). Pin-only chains (TON, …) need no CDN files.
        </PageHint>
      }
      right={
        <Button leftSection={<IconPlus size={16} />} color="teal" onClick={() => setOpen(true)}>
          Add network
        </Button>
      }
    >
      <Stack gap="md" mt="md" {...blockProps('networks.content')}>
        {error && <Alert color="red">{error}</Alert>}
        {added.length === 0 ? (
          <Text c="dimmed" size="sm">
            No networks yet. Add network to get started.
          </Text>
        ) : (
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 3 }} spacing="sm" {...blockProps('networks.list')}>
            {added.map((n) => (
              <NetworkBlock key={n.id} network={n} onRemove={() => void removeNetwork(n)} />
            ))}
          </SimpleGrid>
        )}
        <AddNetworkModal opened={open} items={items} onClose={() => setOpen(false)} onDone={() => void load()} />
      </Stack>
    </AppChrome>
  )
}

function NetworkBlock({
  network: n,
  onRemove,
}: {
  network: NetworkCatalogItem
  onRemove: () => void
}) {
  const envs: NetworkEnvDetail[] = n.env_details?.length ? n.env_details : (n.envs || []).map((id) => ({ id }))
  const notes = n.disk_notes || []
  const showSizeHeader = hasAnySize(envs)

  return (
    <Card className="env-card net-block" padding="sm" {...blockProps(`networks.card.${n.id}`)}>
      <Group justify="space-between" align="center" wrap="nowrap" gap="xs" mb={6}>
        <Group gap={8} wrap="nowrap" style={{ minWidth: 0 }}>
          <NetworkIcon network={n.id} size={16} />
          <Text fw={700} size="sm" lineClamp={1}>
            {n.label}
          </Text>
          <Text size="xs" c="dimmed">
            {n.id}
          </Text>
        </Group>
        <Button
          variant="subtle"
          color="red"
          size="compact-xs"
          leftSection={<IconTrash size={12} />}
          onClick={onRemove}
        >
          Remove
        </Button>
      </Group>

      <Group gap={6} wrap="wrap" mb={8}>
        <Badge size="xs" variant="light" color={n.files_ready ? 'teal' : 'yellow'}>
          {n.files_ready ? 'files on disk' : 'files missing'}
        </Badge>
        {n.pin_only ? (
          <Badge size="xs" variant="light" color="gray" tt="none">
            host pin
          </Badge>
        ) : null}
        {n.one_env_per_host ? (
          <Badge size="xs" variant="light" color="gray" tt="none">
            one env / host
          </Badge>
        ) : null}
      </Group>

      <div className={`net-block__envs${showSizeHeader ? ' net-block__envs--sizes' : ''}`}>
        <div className="net-block__row net-block__row--head">
          <Text size="9px" fw={600} c="dimmed" tt="uppercase" className="net-block__col-env">
            env
          </Text>
          <Text size="9px" fw={600} c="dimmed" tt="uppercase" className="net-block__col-spec">
            host
          </Text>
          <Text size="9px" fw={600} c="dimmed" tt="uppercase" className="net-block__col-meta" ta="right">
            disk
          </Text>
          {showSizeHeader ? (
            <>
              <Text size="9px" fw={600} c="dimmed" tt="uppercase" ta="right" className="net-block__col-size" title="Plan (install / JBOD hint)">
                plan
              </Text>
              <Text size="9px" fw={600} c="dimmed" tt="uppercase" ta="right" className="net-block__col-size" title="Full node footprint">
                full
              </Text>
              <Text size="9px" fw={600} c="dimmed" tt="uppercase" ta="right" className="net-block__col-size" title="Archive / full-history footprint">
                archive
              </Text>
            </>
          ) : null}
        </div>
        {envs.map((e) => {
          const spec = envSpecLabel(e)
          const snap = snapshotLabel(e)
          return (
            <div key={e.id} className="net-block__row net-block__env">
              <Text size="xs" fw={600} c="dimmed" tt="uppercase" className="net-block__col-env" title={e.label || e.id}>
                {envShortLabel(n, e)}
              </Text>
              <Text size="xs" c="dimmed" lineClamp={1} className="net-block__col-spec" title={spec || undefined}>
                {spec || '—'}
              </Text>
              <Group gap={4} wrap="nowrap" justify="flex-end" className="net-block__col-meta">
                <DiskMediaBadges network={n} />
                {snap !== '—' ? (
                  <Badge size="xs" variant="light" color={snap === 'required' ? 'teal' : 'gray'}>
                    Snapshot
                  </Badge>
                ) : null}
              </Group>
              {showSizeHeader ? (
                <>
                  <Text size="xs" className="mono net-block__col-size" c="dimmed" ta="right" title="Plan (install / JBOD hint)">
                    {fmtGiB(e.disk_hint_gib)}
                  </Text>
                  <Text size="xs" className="mono net-block__col-size" c="dimmed" ta="right" title="Full node footprint">
                    {fmtGiB(e.full_node_gib)}
                  </Text>
                  <Text size="xs" className="mono net-block__col-size" c="dimmed" ta="right" title="Archive / full-history footprint">
                    {fmtGiB(e.archive_gib)}
                  </Text>
                </>
              ) : null}
            </div>
          )
        })}
      </div>

      {notes.length > 0 ? (
        <Stack gap={2} mt={8}>
          {notes.map((note) => (
            <Text key={note} size="xs" c="dimmed" title={note} lineClamp={2}>
              {note}
            </Text>
          ))}
        </Stack>
      ) : null}
    </Card>
  )
}

function AddNetworkModal({
  opened,
  items,
  onClose,
  onDone,
}: {
  opened: boolean
  items: NetworkCatalogItem[]
  onClose: () => void
  onDone: () => void
}) {
  const [network, setNetwork] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const choices = useMemo(
    () =>
      items
        .filter((n) => n.status !== 'ready')
        .map((n) => ({ value: n.id, label: n.label || n.id })),
    [items],
  )

  useEffect(() => {
    if (!opened) {
      setNetwork(null)
      setError(null)
      setBusy(false)
    }
  }, [opened])

  async function addToDb() {
    if (!network) return
    setBusy(true)
    setError(null)
    try {
      await api.networkAction(network, 'enable')
      notifications.show({ color: 'teal', message: `${network} added` })
      onDone()
      onClose()
    } catch (e) {
      setError(String((e as Error).message || e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal {...blockProps('modal.add-network')} opened={opened} onClose={onClose} title="Add network" size="md" centered>
      <Stack gap="md" {...blockProps('modal.add-network.body')}>
        {error && <Alert color="red">{error}</Alert>}
        {choices.length === 0 ? (
          <Alert color="yellow">All networks from the catalog are already added.</Alert>
        ) : (
          <Stack>
            <Select
              label="Network"
              data={choices}
              value={network}
              onChange={setNetwork}
              searchable
              required
            />
            <Group justify="flex-end">
              <Button variant="default" onClick={onClose}>
                Cancel
              </Button>
              <Button color="teal" loading={busy} disabled={!network} onClick={() => void addToDb()}>
                Add to database
              </Button>
            </Group>
          </Stack>
        )}
      </Stack>
    </Modal>
  )
}
