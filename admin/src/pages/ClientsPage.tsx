import {
  ActionIcon,
  Alert,
  Anchor,
  Badge,
  Button,
  Card,
  Center,
  Group,
  Loader,
  Modal,
  Progress,
  ScrollArea,
  Select,
  SimpleGrid,
  Stack,
  Text,
  Tooltip,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
  IconAlertTriangle,
  IconCopy,
  IconDownload,
  IconPlus,
  IconRefresh,
  IconTrash,
} from '@tabler/icons-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, type ClientRow, type ClientsPayload, type NetworkCatalogItem } from '../api'
import { AppChrome } from '../components/AppChrome'
import { NetworkIcon } from '../components/NetworkIcon'
import {
  RemoveConfirmInput,
  removeConfirmPhrase,
  removePhraseMatches,
} from '../components/RemoveConfirmInput'
import { copyText } from '../lib/copyText'
import { formatClientVersion } from '../lib/format'
import { networkLabel } from '../lib/networksCatalog'
import { navigate } from '../lib/router'
import { blockProps, blockData } from '../lib/blockId'

const STATUS_COLOR: Record<string, string> = {
  ok: 'teal',
  stale: 'yellow',
  fail: 'red',
  pin: 'gray',
  wait: 'blue',
  missing: 'orange',
  deleted: 'gray',
}

const STATUS_LABEL: Record<string, string> = {
  ok: 'ok',
  stale: 'stale',
  fail: 'fail',
  pin: 'pin',
  wait: 'checking',
  missing: 'no files',
  deleted: 'deleted',
}

function rowKey(row: ClientRow): string {
  return `${row.network}/${row.env}/${row.program || ''}`
}

function needsUpdate(row: ClientRow): boolean {
  return row.status === 'stale' || row.status === 'missing' || row.status === 'deleted'
}

function rowBusy(row: ClientRow, syncing: string | null, downloading: Set<string>): boolean {
  const k = rowKey(row)
  return syncing === k || syncing === 'all' || downloading.has(k)
}

function uniqueNames(xs: string[]): string[] {
  const out: string[] = []
  const seen = new Set<string>()
  for (const x of xs) {
    const s = (x || '').trim()
    if (!s || seen.has(s)) continue
    seen.add(s)
    out.push(s)
  }
  return out
}

function verLabel(raw?: string): string {
  return formatClientVersion(raw || '') || raw || '—'
}

function clientCopyURL(row: ClientRow): string {
  const u = (row.url || '').trim()
  if (u && !u.toLowerCase().startsWith('apt://')) return u
  const src = (row.source || '').trim()
  if (/^https?:\/\//i.test(src)) return src
  if (/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(src)) {
    return `https://github.com/${src}/releases`
  }
  return ''
}

/** Origin dir for one network or env: dest/<network>/ or dest/<network>/<env>/. */
function clientDestPath(root: string, ...segs: string[]): string {
  const parts = [root, ...segs]
    .map((s) => String(s || '').trim().replace(/[/\\]+$/g, '').replace(/^[/\\]+/g, ''))
    .filter(Boolean)
  if (!parts.length) return ''
  return `${parts.join('/')}/`
}

function copyDest(path: string) {
  void copyText(path).then(
    () => notifications.show({ color: 'teal', message: 'Copied path', autoClose: 1500 }),
    () => notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 }),
  )
}

type EnvGroup = { env: string; rows: ClientRow[] }
type NetGroup = { network: string; envs: EnvGroup[]; stale: number }
type RemoveClientTarget = {
  network: string
  /** Set when wiping one env (programs + dest). Omit to wipe the whole network. */
  env?: string
  envs: string[]
  programs: string[]
}

function groupNetworks(rows: ClientRow[]): NetGroup[] {
  const nets: string[] = []
  const byNet = new Map<string, ClientRow[]>()
  for (const row of rows) {
    const n = row.network || ''
    if (!byNet.has(n)) {
      nets.push(n)
      byNet.set(n, [])
    }
    byNet.get(n)!.push(row)
  }
  return nets.map((network) => {
    const list = byNet.get(network) || []
    const envs: string[] = []
    const byEnv = new Map<string, ClientRow[]>()
    for (const row of list) {
      const e = row.env || ''
      if (!byEnv.has(e)) {
        envs.push(e)
        byEnv.set(e, [])
      }
      byEnv.get(e)!.push(row)
    }
    const envGroups = envs.map((env) => ({ env, rows: byEnv.get(env) || [] }))
    return {
      network,
      envs: envGroups,
      stale: list.filter(needsUpdate).length,
    }
  })
}

export function ClientsPage() {
  const [data, setData] = useState<ClientsPayload | null>(null)
  const [loading, setLoading] = useState(true)
  const [probing, setProbing] = useState(false)
  const [syncing, setSyncing] = useState<string | null>(null)
  const [downloading, setDownloading] = useState<Set<string>>(() => new Set())
  const [awaiting, setAwaiting] = useState<Set<string>>(() => new Set())
  const [filterNetwork, setFilterNetwork] = useState<string | null>(null)
  const [removeTarget, setRemoveTarget] = useState<RemoveClientTarget | null>(null)
  const [removeTyped, setRemoveTyped] = useState('')
  const [removing, setRemoving] = useState(false)
  const [addOpen, setAddOpen] = useState(false)
  const [updateTarget, setUpdateTarget] = useState<{
    mode: 'one' | 'all'
    network?: string
    env?: string
    program?: string
  } | null>(null)

  const load = useCallback(async () => {
    try {
      const next = await api.clients()
      setData(next)
      const rows = next.rows ?? []
      setDownloading((prev) => {
        if (prev.size === 0) return prev
        const keep = new Set<string>()
        for (const k of prev) {
          const row = rows.find((r) => rowKey(r) === k)
          if (!row) continue
          if (row.status === 'missing' || row.status === 'wait') keep.add(k)
        }
        return keep
      })
      setAwaiting((prev) => {
        if (prev.size === 0) return prev
        const keep = new Set<string>()
        for (const k of prev) {
          const slash = k.indexOf('/')
          if (slash <= 0) continue
          const net = k.slice(0, slash)
          const env = k.slice(slash + 1)
          if (!rows.some((r) => r.network === net && r.env === env)) keep.add(k)
        }
        return keep
      })
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setLoading(false)
    }
  }, [])

  const inflight = downloading.size > 0 || awaiting.size > 0 || !!syncing || probing

  useEffect(() => {
    void load()
    const ms = inflight ? 2000 : 15_000
    const id = window.setInterval(() => void load(), ms)
    return () => window.clearInterval(id)
  }, [load, inflight])

  useEffect(() => {
    setRemoveTyped('')
  }, [removeTarget])

  const removePhrase = removeConfirmPhrase(removeTarget?.network, removeTarget?.env)
  const removeConfirmed = removePhraseMatches(removeTyped, removePhrase)

  async function probe() {
    setProbing(true)
    try {
      await api.clientsProbe()
      await load()
      notifications.show({ color: 'teal', message: 'Latest versions updated' })
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setProbing(false)
    }
  }

  async function afterAdd(network: string, env: string) {
    const next = await api.clients()
    setData(next)
    setLoading(false)
    setAwaiting((prev) => new Set([...prev, `${network}/${env}`]))
    setDownloading((prev) => {
      const keep = new Set(prev)
      for (const row of next.rows ?? []) {
        if (row.network === network && row.env === env) keep.add(rowKey(row))
      }
      return keep
    })
  }

  async function confirmDelete() {
    if (!removeTarget || !removeConfirmed) return
    const { network, env } = removeTarget
    setRemoving(true)
    try {
      const got = await api.clientsDelete(network, env)
      const label = env ? `${network}/${env}` : network
      const where = got.removed?.length ? got.removed.join(', ') : got.dest || 'origin dest'
      notifications.show({
        color: 'teal',
        message: `Removed ${label} from disk: ${where}`,
      })
      setRemoveTarget(null)
      window.setTimeout(() => void load(), 2500)
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setRemoving(false)
    }
  }

  const rows = data?.rows ?? []
  const tokenReady = !!data?.github_token_set
  const updates = useMemo(() => rows.filter(needsUpdate), [rows])
  const networkFilterOptions = useMemo(() => {
    const seen = new Set<string>()
    const out: { value: string; label: string }[] = []
    for (const row of rows) {
      const n = row.network || ''
      if (!n || seen.has(n)) continue
      seen.add(n)
      out.push({ value: n, label: networkLabel(n) })
    }
    return out
  }, [rows])
  const shown = useMemo(() => {
    if (!filterNetwork) return rows
    return rows.filter((r) => r.network === filterNetwork)
  }, [rows, filterNetwork])
  const groups = useMemo(() => groupNetworks(shown), [shown])

  if (loading && !data) {
    return (
      <Center mih={240}>
        <Stack align="center" gap="sm">
          <Loader color="teal" />
          <Text c="dimmed">Loading clients…</Text>
        </Stack>
      </Center>
    )
  }

  return (
    <AppChrome
      block="clients"
      title="Clients"
      subtitle={<UpdatesHeader rows={updates} />}
      right={
        <Group gap="sm">
          <Button leftSection={<IconPlus size={16} />} color="teal" onClick={() => setAddOpen(true)}>
            Add client
          </Button>
          <Button
            variant="default"
            leftSection={<IconDownload size={16} />}
            onClick={() => setUpdateTarget({ mode: 'all' })}
            disabled={!tokenReady || updates.length === 0 || !!updateTarget}
          >
            Update all{updates.length > 0 ? ` (${updates.length})` : ''}
          </Button>
          <Button
            leftSection={<IconRefresh size={16} />}
            onClick={() => void probe()}
            loading={probing || data?.probing}
            disabled={!tokenReady}
          >
            Check latest
          </Button>
        </Group>
      }
    >
      <Stack gap="md" mt="md" {...blockProps('clients.content')}>
        {data?.error ? (
          <Alert color="red" icon={<IconAlertTriangle size={16} />} title="clients">
            {data.error}
          </Alert>
        ) : null}

        {!tokenReady ? (
          <Alert color="yellow" icon={<IconAlertTriangle size={16} />} title="GitHub token" {...blockProps('clients.token-warning')}>
            Set a GitHub personal access token in{' '}
            <Anchor component="button" type="button" onClick={() => navigate({ name: 'settings' })}>
              Settings
            </Anchor>{' '}
            before probing or downloading clients (rate limits). Add a client after the token is set — Check latest, then Download. The network appears once you Add.
          </Alert>
        ) : null}

        <Group justify="space-between" wrap="wrap" {...blockProps('clients.toolbar')}>
          <Text size="sm" c="dimmed">
            {groups.length} networks · {shown.length} programs
            {data?.probed_at ? ` · ${data.probed_at}` : ''}
          </Text>
          <Select
            placeholder="All networks"
            data={networkFilterOptions}
            value={filterNetwork}
            onChange={setFilterNetwork}
            searchable
            clearable
            nothingFoundMessage="No network"
            w={260}
          />
        </Group>

        {groups.length === 0 ? (
          <Text c="dimmed" size="sm">
            {filterNetwork
              ? 'No clients match the filter'
              : 'No clients yet. Add a client: Check latest, Download, then Add.'}
          </Text>
        ) : (
          <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="sm" {...blockProps('clients.list')}>
            {groups.map((g) => (
              <NetworkBlock
                key={g.network}
                group={g}
                dest={data?.dest || ''}
                syncing={syncing}
                downloading={downloading}
                disabled={!!updateTarget || !!syncing || removing}
                tokenReady={tokenReady}
                onUpdate={(row) =>
                  setUpdateTarget({
                    mode: 'one',
                    network: row.network,
                    env: row.env,
                    program: row.program,
                  })
                }
                onDelete={(target) => setRemoveTarget(target)}
              />
            ))}
          </SimpleGrid>
        )}
      </Stack>

      <Modal
        {...blockProps('modal.remove-client')}
        opened={!!removeTarget}
        onClose={() => (!removing ? setRemoveTarget(null) : undefined)}
        title={removeTarget?.env ? `Delete ${removeTarget.env}?` : 'Delete client?'}
        centered
        size="md"
      >
        <Stack gap="md">
          {removeTarget?.env ? (
            <Text size="sm">
              Wipe origin dest for{' '}
              <Text span fw={700}>
                {removeTarget.network}/{removeTarget.env}
              </Text>
              : every program
              {removeTarget.programs.length ? ` (${removeTarget.programs.join(', ')})` : ''}. That
              is{' '}
              <Text span className="mono">
                {clientDestPath(data?.dest || '', removeTarget.network, removeTarget.env) ||
                  `CLIENT_SYNC_DEST/${removeTarget.network}/${removeTarget.env}/`}
              </Text>{' '}
              (tarballs, VERSION, manifests). Other envs on this network stay. Click Update after
              if you want a fresh fetch.
            </Text>
          ) : (
            <Text size="sm">
              Wipe origin dest for{' '}
              <Text span fw={700}>
                {removeTarget?.network}
              </Text>
              : every env
              {removeTarget?.envs.length ? ` (${removeTarget.envs.join(', ')})` : ''} and every
              program
              {removeTarget?.programs.length ? ` (${removeTarget.programs.join(', ')})` : ''}. That
              is{' '}
              <Text span className="mono">
                {clientDestPath(data?.dest || '', removeTarget?.network || '') ||
                  `CLIENT_SYNC_DEST/${removeTarget?.network}/`}
              </Text>{' '}
              (tarballs, VERSION, manifests). Click Update after if you want a fresh fetch.
            </Text>
          )}
          <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Destructive">
            Files are deleted on the origin host. Running nodes are not stopped.
          </Alert>
          <RemoveConfirmInput
            phrase={removePhrase}
            value={removeTyped}
            onChange={setRemoveTyped}
            disabled={removing}
          />
          <Group justify="flex-end">
            <Button variant="default" disabled={removing} onClick={() => setRemoveTarget(null)}>
              Cancel
            </Button>
            <Button
              color="red"
              loading={removing}
              disabled={!removeConfirmed}
              leftSection={<IconTrash size={14} />}
              onClick={() => void confirmDelete()}
            >
              Delete from origin
            </Button>
          </Group>
        </Stack>
      </Modal>
      <AddClientModal
        opened={addOpen}
        tracked={new Set(rows.map((r) => `${r.network}/${r.env}`))}
        tokenReady={tokenReady}
        onClose={() => setAddOpen(false)}
        onAdded={(network, env) => void afterAdd(network, env)}
      />
      <UpdateClientModal
        opened={!!updateTarget}
        mode={updateTarget?.mode || 'one'}
        network={updateTarget?.network || ''}
        env={updateTarget?.env || ''}
        program={updateTarget?.program}
        seedRows={rows}
        tokenReady={tokenReady}
        onClose={() => setUpdateTarget(null)}
        onDone={() => void load()}
      />
    </AppChrome>
  )
}

function UpdatesHeader({ rows }: { rows: ClientRow[] }) {
  const options = useMemo(() => {
    const seen = new Set<string>()
    const out: { value: string; label: string }[] = []
    for (const row of rows) {
      const n = row.network || ''
      if (!n || seen.has(n)) continue
      seen.add(n)
      const count = rows.filter((r) => r.network === n).length
      out.push({
        value: n,
        label: count > 1 ? `${networkLabel(n)} (${count})` : networkLabel(n),
      })
    }
    return out
  }, [rows])

  if (rows.length === 0) {
    return (
      <Text c="dimmed" size="sm">
        Nothing to update
      </Text>
    )
  }

  return (
    <Group gap="sm" wrap="nowrap" className="clients-updates">
      <Text size="sm" c="dimmed" style={{ flexShrink: 0 }}>
        Update:
      </Text>
      <Select
        size="xs"
        placeholder="Select network…"
        data={options}
        searchable
        clearable
        w={240}
        onChange={(v) => {
          if (!v) return
          document.getElementById(`client-${v}`)?.scrollIntoView({
            behavior: 'smooth',
            block: 'start',
          })
        }}
      />
    </Group>
  )
}

function NetworkBlock({
  group,
  dest,
  syncing,
  downloading,
  disabled,
  tokenReady,
  onUpdate,
  onDelete,
}: {
  group: NetGroup
  dest: string
  syncing: string | null
  downloading: Set<string>
  disabled: boolean
  tokenReady: boolean
  onUpdate: (row: ClientRow) => void
  onDelete: (target: RemoveClientTarget) => void
}) {
  const netPath = clientDestPath(dest, group.network)
  return (
    <Card id={`client-${group.network}`} className="env-card client-net" padding="sm" {...blockData(`clients.network.${group.network}`)}>
      <Group justify="space-between" align="center" wrap="nowrap" gap="xs" mb={netPath ? 4 : 8}>
        <Group gap={8} wrap="nowrap" style={{ minWidth: 0 }}>
          <NetworkIcon network={group.network} size={16} />
          <Text fw={700} size="sm" lineClamp={1}>
            {networkLabel(group.network)}
          </Text>
        </Group>
        <Group gap={6} wrap="nowrap" style={{ flexShrink: 0 }}>
          {group.stale > 0 ? (
            <Badge color="yellow" variant="light" tt="none" size="xs">
              {group.stale} to update
            </Badge>
          ) : (
            <Badge color="teal" variant="light" tt="none" size="xs">
              ok
            </Badge>
          )}
          <Button
            variant="subtle"
            color="red"
            size="compact-xs"
            disabled={disabled}
            onClick={() =>
              onDelete({
                network: group.network,
                envs: group.envs.map((eg) => eg.env),
                programs: uniqueNames(group.envs.flatMap((eg) => eg.rows.map((r) => r.program))),
              })
            }
          >
            Delete all
          </Button>
        </Group>
      </Group>
      {netPath ? (
        <Group gap={4} wrap="nowrap" className="client-net__dest" mb={8}>
          <Text size="xs" c="dimmed" className="mono client-net__dest-path" title={netPath}>
            {netPath}
          </Text>
          <Tooltip label="Copy path">
            <ActionIcon
              variant="subtle"
              size="xs"
              aria-label="Copy dest path"
              onClick={() => copyDest(netPath)}
            >
              <IconCopy size={12} />
            </ActionIcon>
          </Tooltip>
        </Group>
      ) : null}

      <Stack gap={6}>
        {group.envs.map((eg) => {
          return (
            <div key={eg.env} className="client-net__env">
              <Group justify="space-between" align="center" wrap="nowrap" gap="xs" mb={4}>
                <Text size="xs" fw={600} c="dimmed" tt="uppercase">
                  {eg.env}
                </Text>
                <Button
                  variant="subtle"
                  color="red"
                  size="compact-xs"
                  disabled={disabled}
                  onClick={() =>
                    onDelete({
                      network: group.network,
                      env: eg.env,
                      envs: [eg.env],
                      programs: uniqueNames(eg.rows.map((r) => r.program)),
                    })
                  }
                >
                  Delete
                </Button>
              </Group>
              <Stack gap={2}>
                {eg.rows.map((row) => (
                  <ProgramLine
                    key={rowKey(row)}
                    row={row}
                    busy={rowBusy(row, syncing, downloading)}
                    disabled={disabled}
                    updateDisabled={!tokenReady}
                    onUpdate={() => onUpdate(row)}
                  />
                ))}
              </Stack>
            </div>
          )
        })}
      </Stack>
    </Card>
  )
}

function ProgramLine({
  row,
  busy,
  disabled,
  updateDisabled,
  onUpdate,
}: {
  row: ClientRow
  busy: boolean
  disabled: boolean
  updateDisabled: boolean
  onUpdate: () => void
}) {
  const canUpdate = needsUpdate(row) || row.status === 'wait'
  const cur = verLabel(row.pin)
  const latest = verLabel(row.latest)
  const hasCur = cur !== '—'
  const hasLatest = latest !== '—'
  const isInstall = !hasCur || row.status === 'missing' || row.status === 'deleted' || row.status === 'wait'
  const showArrow = canUpdate && hasCur && hasLatest && cur !== latest
  const ver = showArrow ? `${cur} → ${latest}` : hasCur ? cur : hasLatest ? latest : ''
  const copyURL = clientCopyURL(row)
  const statusLabel = busy && (row.status === 'missing' || row.status === 'wait')
    ? 'downloading'
    : STATUS_LABEL[row.status] || row.status
  const statusColor = busy && (row.status === 'missing' || row.status === 'wait')
    ? 'blue'
    : STATUS_COLOR[row.status] || 'gray'
  return (
    <div className="client-prog">
      <Text size="sm" fw={600} className="client-prog__name">
        {row.program || row.network}
      </Text>
      <Text
        size="xs"
        className="mono client-prog__ver"
        c={showArrow ? 'yellow.4' : 'dimmed'}
        title={ver || undefined}
      >
        {ver}
      </Text>
      <Group gap={4} wrap="nowrap" className="client-prog__actions">
        <Tooltip label={row.probe_error || statusLabel}>
          <Badge color={statusColor} variant="light" tt="none" size="xs" leftSection={busy ? <Loader size={10} color="blue" /> : undefined}>
            {statusLabel}
          </Badge>
        </Tooltip>
        {canUpdate ? (
          <Tooltip label={isInstall ? 'Install latest' : 'Update to latest'}>
            <ActionIcon
              variant="subtle"
              size="sm"
              aria-label={isInstall ? 'Install' : 'Update'}
              loading={busy}
              disabled={updateDisabled || (disabled && !busy)}
              onClick={onUpdate}
            >
              <IconDownload size={14} />
            </ActionIcon>
          </Tooltip>
        ) : null}
        {copyURL ? (
          <Tooltip label="Copy URL">
            <ActionIcon
              variant="subtle"
              size="sm"
              aria-label="Copy URL"
              onClick={() =>
                void copyText(copyURL).then(
                  () => notifications.show({ color: 'teal', message: 'Copied', autoClose: 1500 }),
                  () => notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 }),
                )
              }
            >
              <IconCopy size={13} />
            </ActionIcon>
          </Tooltip>
        ) : null}
      </Group>
    </div>
  )
}

function clientRowReady(row: ClientRow): boolean {
  if ((row.pin || '').trim()) return true
  return (row.download_phase || '') === 'done'
}

function clientRowFailed(row: ClientRow): boolean {
  if ((row.download_phase || '') === 'fail') return true
  return !!(row.download_error || '').trim()
}

function fmtBytes(n: number): string {
  if (!n || n < 0) return '0 B'
  if (n < 1024) return `${Math.round(n)} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  return `${(n / (1024 * 1024)).toFixed(1)} MB`
}

function UpdateClientModal({
  opened,
  mode,
  network,
  env,
  program,
  seedRows,
  tokenReady,
  onClose,
  onDone,
}: {
  opened: boolean
  mode: 'one' | 'all'
  network: string
  env: string
  program?: string
  seedRows: ClientRow[]
  tokenReady: boolean
  onClose: () => void
  onDone: () => void
}) {
  const [downloading, setDownloading] = useState(false)
  const [done, setDone] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<ClientRow[]>([])
  const jobRef = useRef<ClientRow[]>([])
  const syncAcceptedRef = useRef(false)

  useEffect(() => {
    if (!opened) {
      setDownloading(false)
      setDone(false)
      setError(null)
      setPreview([])
      jobRef.current = []
      syncAcceptedRef.current = false
      return
    }
    const next =
      mode === 'all'
        ? seedRows.filter(needsUpdate)
        : seedRows.filter((r) => {
            if (r.network !== network || r.env !== env) return false
            if (program && r.program && r.program !== program) return false
            return true
          })
    jobRef.current = next
    setPreview(next)
    setDone(false)
    setError(null)
    setDownloading(false)
    syncAcceptedRef.current = false
    // Freeze job when modal opens / target changes — not on every parent poll.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [opened, mode, network, env, program])

  useEffect(() => {
    if (!opened || !downloading) return
    let stop = false
    const tick = async () => {
      while (!stop) {
        try {
          const job = jobRef.current
          if (job.length === 0) {
            setDownloading(false)
            setDone(true)
            return
          }
          const groupKeys = uniqueNames(job.map((r) => `${r.network}/${r.env}`))
          const previewByKey = new Map<string, ClientRow>()
          for (const ge of groupKeys) {
            const slash = ge.indexOf('/')
            const net = ge.slice(0, slash)
            const envId = ge.slice(slash + 1)
            const data = await api.clientsPreview(net, envId)
            if (stop) return
            for (const row of data.rows || []) {
              if (mode === 'one' && program && row.program && row.program !== program) continue
              if (!job.some((j) => rowKey(j) === rowKey(row))) continue
              previewByKey.set(rowKey(row), row)
            }
          }
          const list = await api.clients()
          if (stop) return
          const listByKey = new Map<string, ClientRow>()
          for (const row of list.rows || []) {
            if (job.some((j) => rowKey(j) === rowKey(row))) {
              listByKey.set(rowKey(row), row)
            }
          }
          // Prefer list pins/status; overlay in-flight download_* from preview only.
          const merged = job.map((j) => {
            const live = listByKey.get(rowKey(j)) || previewByKey.get(rowKey(j)) || j
            const prev = previewByKey.get(rowKey(j))
            const phase = (prev?.download_phase || '').trim()
            const flying =
              phase === 'queued' ||
              phase === 'download' ||
              phase === 'extract' ||
              phase === 'write'
            if (flying && prev) {
              return {
                ...live,
                download_phase: prev.download_phase,
                download_name: prev.download_name,
                download_bytes: prev.download_bytes,
                download_total: prev.download_total,
                download_pct: prev.download_pct,
                download_error: prev.download_error,
              }
            }
            if (!needsUpdate(live)) {
              return {
                ...live,
                download_phase: 'done',
                download_pct: 100,
                download_error: '',
              }
            }
            return {
              ...live,
              download_phase: prev?.download_phase === 'fail' ? 'fail' : '',
              download_error: prev?.download_error || live.download_error,
              download_pct: 0,
            }
          })
          setPreview(merged)

          const failed = merged.find(clientRowFailed)
          const inFlight = merged.some((r) => {
            const phase = (r.download_phase || '').trim()
            return (
              phase === 'queued' ||
              phase === 'download' ||
              phase === 'extract' ||
              phase === 'write'
            )
          })
          if (failed && !inFlight && syncAcceptedRef.current) {
            setDownloading(false)
            setError(failed.download_error || `Download failed for ${failed.program || failed.network}`)
            return
          }
          const finished =
            syncAcceptedRef.current &&
            !inFlight &&
            merged.length === job.length &&
            merged.every((r) => !needsUpdate(r))
          if (finished) {
            setDownloading(false)
            setDone(true)
            setError(null)
            return
          }
        } catch (e) {
          if (stop) return
          setDownloading(false)
          setError(String((e as Error).message || e))
          return
        }
        await new Promise((r) => setTimeout(r, 1000))
      }
    }
    void tick()
    return () => {
      stop = true
    }
  }, [opened, downloading, mode, program])

  useEffect(() => {
    if (!done || !opened) return
    const t = window.setTimeout(() => {
      onDone()
      onClose()
    }, 600)
    return () => window.clearTimeout(t)
    // Intentionally only when done flips — parent inline callbacks must not reset the timer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [done, opened])

  async function startDownload() {
    const job = jobRef.current
    if (job.length === 0) return
    syncAcceptedRef.current = false
    setDownloading(true)
    setDone(false)
    setError(null)
    setPreview(
      job.map((r) =>
        (r.skip_reason || '').trim()
          ? r
          : { ...r, download_phase: 'queued', download_pct: 0, download_error: '' },
      ),
    )
    try {
      const groupKeys = uniqueNames(job.map((r) => `${r.network}/${r.env}`))
      if (mode === 'all') {
        for (const ge of groupKeys) {
          const slash = ge.indexOf('/')
          await api.clientsSync({
            network: ge.slice(0, slash),
            env: ge.slice(slash + 1),
            force: true,
          })
        }
      } else {
        await api.clientsSync({
          network,
          env,
          program: program || undefined,
          force: true,
        })
      }
      syncAcceptedRef.current = true
    } catch (e) {
      syncAcceptedRef.current = false
      setDownloading(false)
      setError(String((e as Error).message || e))
    }
  }

  const locked = downloading
  const title =
    mode === 'all'
      ? `Update all (${jobRef.current.length || preview.length})`
      : `Update ${network}/${env}`
  const shown = preview

  function handleClose() {
    if (downloading) return
    if (done) onDone()
    onClose()
  }

  return (
    <Modal
      {...blockProps('modal.update-client')}
      opened={opened}
      onClose={handleClose}
      title={title}
      centered
      size={mode === 'all' && shown.length > 4 ? 'lg' : 'md'}
      closeOnClickOutside={!locked}
      closeOnEscape={!locked}
      withCloseButton={!locked}
    >
      <Stack gap="md">
        {error ? <Alert color="red">{error}</Alert> : null}
        {done ? (
          <Alert color="teal">Updated successfully.</Alert>
        ) : !tokenReady ? (
          <Alert color="yellow">
            Set a GitHub token in{' '}
            <Anchor component="button" type="button" onClick={() => navigate({ name: 'settings' })}>
              Settings
            </Anchor>{' '}
            first. Download needs the token.
          </Alert>
        ) : (
          <Text size="sm" c="dimmed">
            {mode === 'all'
              ? 'All stale clients below. Download pulls the latest for each program with live progress.'
              : 'Current → latest for each program. Download shows progress until the pin matches.'}
          </Text>
        )}

        {shown.length === 0 ? (
          <Text size="sm" c="dimmed">
            Nothing to update.
          </Text>
        ) : (
          <Stack gap="sm">
            <Text size="sm" fw={600}>
              {downloading ? 'Download' : done ? 'Updated' : 'Versions'}
            </Text>
            <ScrollArea.Autosize mah={420} type="auto" offsetScrollbars>
              <Stack gap="sm">
                {shown.map((row) => (
                  <UpdateProgramLine key={rowKey(row)} row={row} active={downloading || done} />
                ))}
              </Stack>
            </ScrollArea.Autosize>
          </Stack>
        )}

        <Group justify="flex-end">
          <Button variant="default" disabled={downloading} onClick={handleClose}>
            {done ? 'Close' : 'Cancel'}
          </Button>
          {!done ? (
            <Button
              color="orange"
              loading={downloading}
              disabled={!tokenReady || shown.length === 0}
              onClick={() => void startDownload()}
            >
              {error && !downloading ? 'Retry download' : mode === 'all' ? 'Update all' : 'Download'}
            </Button>
          ) : null}
        </Group>
      </Stack>
    </Modal>
  )
}

function UpdateProgramLine({ row, active }: { row: ClientRow; active: boolean }) {
  const cur = verLabel(row.pin)
  const latest = verLabel(row.latest)
  const failed = clientRowFailed(row)
  const phase = (row.download_phase || '').trim()
  const pct = Math.max(0, Math.min(100, Number(row.download_pct) || 0))
  const updated = !needsUpdate(row) && !!(row.pin || '').trim()
  const flying =
    phase === 'queued' || phase === 'download' || phase === 'extract' || phase === 'write'
  const showBar = active && !row.skip_reason && (flying || updated || failed)
  const unknown = showBar && flying && (phase !== 'download' || !(row.download_total && row.download_total > 0))

  const versionLabel =
    cur !== '—' && latest !== '—' && cur !== latest
      ? `${cur} → ${latest}`
      : latest !== '—'
        ? latest
        : cur

  let detail = versionLabel
  if (failed) detail = row.download_error || 'failed'
  else if (flying && phase === 'download' && row.download_name) {
    const size =
      row.download_total && row.download_total > 0
        ? `${fmtBytes(row.download_bytes || 0)} / ${fmtBytes(row.download_total)}`
        : fmtBytes(row.download_bytes || 0)
    detail = `${versionLabel} · ${row.download_name} · ${size}`
  } else if (flying && (phase === 'queued' || !phase)) detail = `${versionLabel} · queued`
  else if (updated) detail = cur !== '—' ? cur : 'done'

  return (
    <Stack gap={4}>
      <Group justify="space-between" wrap="nowrap" gap="sm">
        <Text size="sm">
          {row.program || row.network}
          {row.env ? (
            <Text span size="xs" c="dimmed">
              {' '}
              · {row.network}/{row.env}
            </Text>
          ) : null}
        </Text>
        <Text
          size="sm"
          className="mono"
          c={failed ? 'red' : !updated && cur !== latest ? 'yellow.6' : 'dimmed'}
        >
          {detail}
        </Text>
      </Group>
      {showBar ? (
        <Progress
          value={updated || failed ? 100 : unknown ? 100 : pct}
          color={failed ? 'red' : updated ? 'teal' : 'teal'}
          size="sm"
          striped={unknown || (flying && !updated)}
          animated={unknown || (flying && !updated)}
        />
      ) : null}
    </Stack>
  )
}

function AddClientModal({
  opened,
  tracked,
  tokenReady,
  onClose,
  onAdded,
}: {
  opened: boolean
  tracked: Set<string>
  tokenReady: boolean
  onClose: () => void
  onAdded: (network: string, env: string) => void
}) {
  const [items, setItems] = useState<NetworkCatalogItem[]>([])
  const [network, setNetwork] = useState<string | null>(null)
  const [env, setEnv] = useState<string | null>(null)
  const [release, setRelease] = useState<{ version: string; tag: string; source: string } | null>(null)
  const [versionLoading, setVersionLoading] = useState(false)
  const [downloading, setDownloading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [preview, setPreview] = useState<ClientRow[] | null>(null)
  const [want, setWant] = useState(0)

  useEffect(() => {
    if (!opened) {
      setNetwork(null)
      setEnv(null)
      setRelease(null)
      setVersionLoading(false)
      setError(null)
      setDownloading(false)
      setPreview(null)
      setWant(0)
      return
    }
    void api.networksAll().then(
      (r) => setItems(r.items || []),
      (e) => setError(String((e as Error).message || e)),
    )
  }, [opened])

  useEffect(() => {
    if (!opened || !network || !env) {
      setRelease(null)
      setVersionLoading(false)
      return
    }
    let stop = false
    setVersionLoading(true)
    setRelease(null)
    setError(null)
    void api
      .clientsVersion(network, env)
      .then((r) => {
        if (stop) return
        const version = (r.version || '').trim()
        if (!version) {
          setRelease(null)
          setError('No client release for this env.')
          return
        }
        setRelease({
          version,
          tag: (r.tag || '').trim(),
          source: (r.source || '').trim(),
        })
      })
      .catch((e) => {
        if (stop) return
        setRelease(null)
        setError(String((e as Error).message || e))
      })
      .finally(() => {
        if (!stop) setVersionLoading(false)
      })
    return () => {
      stop = true
    }
  }, [opened, network, env])

  useEffect(() => {
    if (!opened || !downloading || !network || !env) return
    let stop = false
    const tick = async () => {
      while (!stop) {
        try {
          const data = await api.clientsPreview(network, env)
          if (stop) return
          const rows = data.rows || []
          const need = data.want && data.want > 0 ? data.want : Math.max(want, rows.length, 1)
          setWant(need)
          setPreview((prev) => (rows.length ? rows : prev))
          if (rows.length >= need && rows.every(clientRowReady)) {
            setDownloading(false)
            setError(null)
            return
          }
          const inFlight = rows.some((r) => !clientRowReady(r) && !clientRowFailed(r))
          const failed = rows.find(clientRowFailed)
          if (failed && !inFlight) {
            setDownloading(false)
            setError(failed.download_error || `Download failed for ${failed.program || failed.network}`)
            return
          }
        } catch (e) {
          if (stop) return
          setDownloading(false)
          setError(String((e as Error).message || e))
          return
        }
        await new Promise((r) => setTimeout(r, 1000))
      }
    }
    void tick()
    return () => {
      stop = true
    }
  }, [opened, downloading, network, env, want])

  const selectedNet = useMemo(() => items.find((x) => x.id === network) || null, [items, network])
  const pinOnly = !!selectedNet?.pin_only

  const netChoices = useMemo(
    () =>
      items.map((n) => ({
        value: n.id,
        label: n.label
          ? `${n.label} (${n.id})${n.pin_only ? ' · pin' : ''}`
          : `${n.id}${n.pin_only ? ' · pin' : ''}`,
      })),
    [items],
  )
  const envChoices = useMemo(() => {
    const n = items.find((x) => x.id === network)
    return (n?.envs || []).map((id) => {
      const taken = tracked.has(`${network}/${id}`)
      return { value: id, label: taken ? `${id} (added)` : id, disabled: taken }
    })
  }, [items, network, tracked])

  async function startDownload() {
    if (!network || !env || !release) return
    setDownloading(true)
    setError(null)
    setPreview((prev) =>
      (prev || []).map((r) =>
        (r.skip_reason || '').trim()
          ? r
          : { ...r, download_phase: 'queued', download_pct: 0, download_error: '' },
      ),
    )
    try {
      const got = await api.clientsAdd(network, env)
      if (got.probe === 'need_token' && !pinOnly) {
        setDownloading(false)
        setError('Set a GitHub token in Settings, then download again.')
        return
      }
      // Pin-only: AddClient already wrote the DB pin — still sync install-plan/manifest under clients/.
      await api.clientsSync({ network, env, force: true })
    } catch (e) {
      setDownloading(false)
      setError(String((e as Error).message || e))
    }
  }

  const locked = downloading || versionLoading
  const canDownload = !!release && !versionLoading
  const allReady =
    !!preview?.length && preview.length >= Math.max(want, 1) && preview.every(clientRowReady)

  function handleClose() {
    if (downloading || versionLoading) return
    if (allReady && network && env) onAdded(network, env)
    onClose()
  }

  return (
    <Modal
      {...blockProps('modal.add-client')}
      opened={opened}
      onClose={handleClose}
      title="Add client"
      centered
      size="md"
      closeOnClickOutside={!locked}
      closeOnEscape={!locked}
      withCloseButton={!locked}
    >
      <Stack gap="md">
        {error ? <Alert color="red">{error}</Alert> : null}
        {pinOnly ? (
          <Alert color="blue">
            Pin-only network — no CDN tarball. Add registers the host install pin (MyTonCtrl /
            similar); binaries are provisioned on the node at Start.
          </Alert>
        ) : !tokenReady ? (
          <Alert color="yellow">
            Set a GitHub token in{' '}
            <Anchor component="button" type="button" onClick={() => navigate({ name: 'settings' })}>
              Settings
            </Anchor>{' '}
            first. Latest version is read from GitHub; Download needs the token.
          </Alert>
        ) : (
          <Text size="sm" c="dimmed">
            Pick a network and env. Latest client version is resolved for that env, then Download
            writes it under the clients directory and pins it in the database.
          </Text>
        )}
        <Select
          label="Network"
          data={netChoices}
          value={network}
          onChange={(v) => {
            setNetwork(v)
            setEnv(null)
            setRelease(null)
            setPreview(null)
            setError(null)
            setWant(0)
          }}
          searchable
          required
          disabled={locked}
        />
        <Select
          label="Env"
          data={envChoices}
          value={env}
          onChange={(v) => {
            setEnv(v)
            setRelease(null)
            setPreview(null)
            setError(null)
            setWant(0)
          }}
          disabled={!network || locked}
          required
        />
        {versionLoading ? (
          <Group gap="sm">
            <Loader size="sm" color="teal" />
            <Text size="sm" c="dimmed">
              Resolving latest client version…
            </Text>
          </Group>
        ) : null}
        {release && !preview?.length ? (
          <Stack gap={4}>
            <Text size="sm" fw={600}>
              Latest
            </Text>
            <Group justify="space-between" wrap="nowrap" gap="sm">
              <Text size="sm" c="dimmed">
                {release.source || 'release'}
              </Text>
              <Text size="sm" className="mono">
                {formatClientVersion(release.version) || release.version}
                {release.tag && release.tag !== release.version ? ` · ${release.tag}` : ''}
              </Text>
            </Group>
          </Stack>
        ) : null}
        {preview && preview.length > 0 ? (
          <Stack gap="sm">
            <Text size="sm" fw={600}>
              {downloading ? 'Download' : allReady ? 'On disk' : 'Latest'}
            </Text>
            {preview.map((row) => (
              <ProgramDownloadLine key={rowKey(row)} row={row} active={downloading || allReady} />
            ))}
          </Stack>
        ) : null}
        <Group justify="flex-end">
          <Button variant="default" disabled={downloading} onClick={handleClose}>
            {allReady ? 'Close' : 'Cancel'}
          </Button>
          {!preview && !release ? (
            <Button color="teal" disabled>
              Select network and env
            </Button>
          ) : allReady ? (
            <Button color="teal" onClick={handleClose}>
              Add
            </Button>
          ) : (
            <Button
              color="teal"
              loading={downloading}
              disabled={!canDownload || (!pinOnly && !tokenReady)}
              onClick={() => void startDownload()}
            >
              {error && !downloading
                ? pinOnly
                  ? 'Retry add'
                  : 'Retry download'
                : pinOnly
                  ? 'Add'
                  : 'Download'}
            </Button>
          )}
        </Group>
      </Stack>
    </Modal>
  )
}

function ProgramDownloadLine({ row, active }: { row: ClientRow; active: boolean }) {
  const latest = verLabel(row.latest)
  const failed = clientRowFailed(row)
  const ready = clientRowReady(row)
  const phase = (row.download_phase || '').trim()
  const pct = Math.max(0, Math.min(100, Number(row.download_pct) || 0))
  const showBar = active && !row.skip_reason && (ready || !failed)
  const unknown =
    showBar && !ready && (phase !== 'download' || !(row.download_total && row.download_total > 0))
  let detail = latest !== '—' ? latest : ''
  if (row.probe_error) detail = row.probe_error
  else if (row.skip_reason && ready) detail = verLabel(row.pin) !== '—' ? verLabel(row.pin) : row.skip_reason
  else if (row.skip_reason && active) detail = 'pinning…'
  else if (row.skip_reason) detail = row.skip_reason
  else if (failed) detail = row.download_error || 'failed'
  else if (ready && (row.pin || '').trim()) detail = verLabel(row.pin)
  else if (phase === 'queued' || (active && !phase)) detail = 'queued'
  else if (phase === 'download' && row.download_name) {
    const size =
      row.download_total && row.download_total > 0
        ? `${fmtBytes(row.download_bytes || 0)} / ${fmtBytes(row.download_total)}`
        : fmtBytes(row.download_bytes || 0)
    detail = `${row.download_name} · ${size}`
  }
  return (
    <Stack gap={4}>
      <Group justify="space-between" wrap="nowrap" gap="sm">
        <Text size="sm">{row.program || row.network}</Text>
        <Text size="sm" className="mono" c={failed || row.probe_error ? 'red' : 'dimmed'}>
          {detail}
        </Text>
      </Group>
      {showBar ? (
        <Progress
          value={unknown ? 100 : ready ? 100 : pct}
          color={failed ? 'red' : 'teal'}
          size="sm"
          striped={unknown || (phase === 'download' && !ready)}
          animated={unknown || (phase === 'download' && !ready)}
        />
      ) : null}
    </Stack>
  )
}

