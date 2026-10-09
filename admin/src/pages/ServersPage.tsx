import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Card,
  Group,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Loader,
  Center,
  Code,
  Modal,
  TextInput,
  PasswordInput,
  Tooltip,
} from '@mantine/core'
import {
  IconAlertTriangle,
  IconDownload,
  IconFileText,
  IconPencil,
  IconPlus,
  IconRefresh,
  IconSearch,
  IconServer,
  IconTrash,
} from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, type RegistryNode, type ServerMetrics } from '../api'
import { AppChrome } from '../components/AppChrome'
import { AddServerModal } from '../components/AddServerModal'
import { DiscoverNodesModal } from '../components/DiscoverNodesPanel'
import { CopyMaskedUrl } from '../components/CopyMaskedUrl'
import { RemoveConfirmInput, removePhraseMatches } from '../components/RemoveConfirmInput'
import { ServerLogsModal } from '../components/ServerLogsModal'
import { agentVersionOutdated } from '../lib/agentVersion'
import { maskHostname } from '../lib/maskHost'
import { navigate } from '../lib/router'
import { blockProps } from '../lib/blockId'

function displayServerName(s: RegistryNode): string {
  const name = (s.name || s.id || '').trim()
  if (/^\d{1,3}(\.\d{1,3}){3}$/.test(name)) return maskHostname(name)
  return name || s.id
}

type MetricLevel = 'ok' | 'warn' | 'critical'

function sleep(ms: number) {
  return new Promise<void>((r) => setTimeout(r, ms))
}

/** After CDN install the tip schedules unit restart async — wait before trusting version. */
const AGENT_UPDATE_SETTLE_SEC = 10
const AGENT_UPDATE_POLL_MS = 45_000

function platformLabel(s: RegistryNode): string {
  if (s.os_pretty) return s.os_pretty
  if (s.os || s.arch) return [s.os, s.arch].filter(Boolean).join('/')
  return ''
}

function statusColor(status?: string): string {
  switch (status) {
    case 'online':
      return 'teal'
    case 'stale':
      return 'yellow'
    case 'removing':
      return 'orange'
    case 'offline':
      return 'red'
    default:
      return 'gray'
  }
}

/** Utilization bands — same teal/yellow/red as server status badges. */
function metricLevel(pct?: number): MetricLevel | undefined {
  if (pct == null || Number.isNaN(pct)) return undefined
  if (pct >= 90) return 'critical'
  if (pct >= 70) return 'warn'
  return 'ok'
}

function metricValueColor(level?: MetricLevel): string | undefined {
  switch (level) {
    case 'ok':
      return 'teal'
    case 'warn':
      return 'yellow'
    case 'critical':
      return 'red'
    default:
      return undefined
  }
}

function fmtPct(n?: number): string {
  if (n == null || Number.isNaN(n)) return '—'
  return `${n.toFixed(0)}%`
}

function fmtMem(m?: ServerMetrics | null): string {
  if (!m || !m.mem_total_mb) return '—'
  const used = m.mem_used_mb ?? 0
  const total = m.mem_total_mb
  const u = used >= 1024 ? `${(used / 1024).toFixed(1)}` : `${used.toFixed(0)}`
  const t = total >= 1024 ? `${(total / 1024).toFixed(1)}` : `${total.toFixed(0)}`
  const unit = total >= 1024 ? 'GB' : 'MB'
  return `${u}/${t} ${unit}`
}

function fmtDisk(m?: ServerMetrics | null): string {
  const disks = (m?.disks || []).filter((d) => (d.total_gb ?? 0) > 0)
  if (disks.length > 0) {
    return disks
      .map((d) => {
        const total = d.total_gb ?? 0
        const used = Math.max(0, total - (d.free_gb ?? 0))
        const label = (d.name || d.mount || 'disk').trim()
        return `${label} ${used.toFixed(0)}/${total.toFixed(0)} GB`
      })
      .join(' · ')
  }
  if (!m || !(m.disk_total_gb && m.disk_total_gb > 0)) return '—'
  const used = m.disk_used_gb ?? 0
  const total = m.disk_total_gb
  const pct =
    m.disk_used_pct != null && !Number.isNaN(m.disk_used_pct) ? ` · ${m.disk_used_pct.toFixed(0)}%` : ''
  return `${used.toFixed(0)}/${total.toFixed(0)} GB${pct}`
}

function diskHint(m?: ServerMetrics | null): string {
  const disks = (m?.disks || []).filter((d) => (d.total_gb ?? 0) > 0)
  if (disks.length > 0) {
    return disks
      .map((d) => {
        const mount = d.mount || '/'
        const free = d.free_gb ?? 0
        const total = d.total_gb ?? 0
        return `${d.name || 'disk'} ${mount}: ${free.toFixed(0)}/${total.toFixed(0)} GB free`
      })
      .join(' · ')
  }
  return 'Root filesystem used / total'
}

function fmtLoad(n?: number): string {
  if (n == null || Number.isNaN(n)) return '—'
  return n.toFixed(2)
}

/** CPU util from agent /proc/stat (mpstat-like). Never substitute load avg. */
function displayCPUPct(m?: ServerMetrics | null): number | undefined {
  if (!m) return undefined
  const cpu = m.cpu_pct
  if (cpu == null || Number.isNaN(cpu)) return undefined
  return cpu
}

function memUsedPct(m?: ServerMetrics | null): number | undefined {
  if (!m) return undefined
  if (m.mem_pct != null && !Number.isNaN(m.mem_pct) && m.mem_pct > 0) return m.mem_pct
  if (m.mem_total_mb != null && m.mem_total_mb > 0 && m.mem_used_mb != null && !Number.isNaN(m.mem_used_mb)) {
    return (m.mem_used_mb / m.mem_total_mb) * 100
  }
  return undefined
}

function diskUsedPct(m?: ServerMetrics | null): number | undefined {
  if (!m) return undefined
  if (m.disk_used_pct != null && !Number.isNaN(m.disk_used_pct)) return m.disk_used_pct
  if (m.disk_total_gb != null && m.disk_total_gb > 0 && m.disk_used_gb != null && !Number.isNaN(m.disk_used_gb)) {
    return (m.disk_used_gb / m.disk_total_gb) * 100
  }
  return undefined
}

/** load_1 / ncpu * 100 when agent exposes load_pct (or ncpu for derive). */
function loadPressurePct(m?: ServerMetrics | null): number | undefined {
  if (!m) return undefined
  if (m.load_pct != null && !Number.isNaN(m.load_pct)) return m.load_pct
  const ncpu = m.ncpu
  if (ncpu != null && ncpu > 0 && m.load_1 != null && !Number.isNaN(m.load_1)) {
    return (m.load_1 / ncpu) * 100
  }
  return undefined
}

function MetricCell({
  label,
  value,
  hint,
  level,
}: {
  label: string
  value: string
  hint?: string
  level?: MetricLevel
}) {
  const valueColor = metricValueColor(level)

  return (
    <div className="server-card__metric" title={hint || value}>
      <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.4 }}>
        {label}
      </Text>
      <Text
        size="sm"
        fw={600}
        c={valueColor}
        className="mono server-card__metric-value"
        data-level={level || undefined}
      >
        {value}
      </Text>
    </div>
  )
}

function hasMetrics(m?: ServerMetrics | null): boolean {
  if (!m) return false
  return Boolean(
    (m.cpu_pct != null && !Number.isNaN(m.cpu_pct)) ||
      (m.mem_total_mb != null && m.mem_total_mb > 0) ||
      (m.disk_total_gb != null && m.disk_total_gb > 0) ||
      (m.disks != null && m.disks.some((d) => (d.total_gb ?? 0) > 0)) ||
      (m.load_1 != null && !Number.isNaN(m.load_1)),
  )
}

export function ServersPage() {
  const [items, setItems] = useState<RegistryNode[]>([])
  const [channelLatest, setChannelLatest] = useState('')
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [addOpen, setAddOpen] = useState(false)
  const [removeTarget, setRemoveTarget] = useState<RegistryNode | null>(null)
  const [removing, setRemoving] = useState(false)
  const [removeTyped, setRemoveTyped] = useState('')
  const [purgingNodes, setPurgingNodes] = useState(false)
  const removePhrase = (removeTarget?.name || removeTarget?.id || '').trim()
  const removeConfirmed =
    !!removeTarget && removePhrase !== '' && removePhraseMatches(removeTyped, removePhrase)
  useEffect(() => {
    if (removeTarget) setRemoveTyped('')
  }, [removeTarget])
  const [editTarget, setEditTarget] = useState<RegistryNode | null>(null)
  const [editURL, setEditURL] = useState('')
  const [editName, setEditName] = useState('')
  const [editKey, setEditKey] = useState('')
  const [editSaving, setEditSaving] = useState(false)
  const [logsTarget, setLogsTarget] = useState<RegistryNode | null>(null)
  const [discoverTarget, setDiscoverTarget] = useState<RegistryNode | null>(null)
  const [updateTarget, setUpdateTarget] = useState<RegistryNode | null>(null)
  const [updatingId, setUpdatingId] = useState<string | null>(null)
  const [updatingCountdown, setUpdatingCountdown] = useState(0)
  const [updateAllOpen, setUpdateAllOpen] = useState(false)
  const [updateAllBusy, setUpdateAllBusy] = useState(false)
  const [updateAllProgress, setUpdateAllProgress] = useState('')

  function openEdit(s: RegistryNode) {
    setEditTarget(s)
    setEditURL(s.agent_url || '')
    setEditName(s.name || s.id)
    setEditKey('')
  }

  async function saveEdit() {
    if (!editTarget) return
    const url = editURL.trim().replace(/\/$/, '')
    if (!url) {
      notifications.show({ color: 'yellow', message: 'Agent URL is required' })
      return
    }
    setEditSaving(true)
    try {
      const res = await api.registryUpdate(editTarget.id, {
        name: editName.trim() || editTarget.name || editTarget.id,
        agent_url: url,
        agent_key: editKey.trim() || undefined,
        network: editTarget.network || '',
        env: editTarget.env || 'mainnet',
      })
      notifications.show({
        color: 'teal',
        message: `Updated ${res.item?.name || editTarget.id} → ${url}`,
      })
      setEditTarget(null)
      void load({ refresh: true })
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setEditSaving(false)
    }
  }

  const load = useCallback(async (opts?: { refresh?: boolean }) => {
    if (opts?.refresh) setRefreshing(true)
    else setLoading(true)
    try {
      const [res, ch] = await Promise.all([
        api.registryList(),
        api.agentChannel({ refresh: !!opts?.refresh }).catch(() => null),
      ])
      const nextItems = res.items || []
      const fromChannel = (ch?.version || '').trim()
      const fromList = (res.latest_agent_version || '').trim()
      const latest = fromChannel || fromList
      setItems(nextItems)
      setChannelLatest(latest)
      setError(null)
      return { items: nextItems, channelLatest: latest }
    } catch (e) {
      setError(String((e as Error).message || e))
      return { items: [] as RegistryNode[], channelLatest: '' }
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [])

  const outdatedServers = useMemo(() => {
    const latest = channelLatest.trim()
    if (!latest) return []
    return items.filter((s) =>
      agentVersionOutdated(s.agent_version || '', latest || s.latest_agent_version || ''),
    )
  }, [items, channelLatest])

  const hasRemoving = items.some((s) => s.remove_status === 'removing' || s.metrics_status === 'removing')

  useEffect(() => {
    void load()
  }, [load])

  useEffect(() => {
    const t = window.setInterval(() => void load({ refresh: true }), hasRemoving ? 2_000 : 60_000)
    return () => window.clearInterval(t)
  }, [load, hasRemoving])

  /** Settle delay + poll until registry shows version ≥ expected (restart is async). */
  async function waitAgentVersionVisible(
    serverId: string,
    expectedVersion: string,
    onProgress?: (msg: string) => void,
  ): Promise<{ version: string; visible: boolean }> {
    const want = expectedVersion.trim().replace(/^v/i, '')
    for (let n = AGENT_UPDATE_SETTLE_SEC; n >= 1; n--) {
      setUpdatingCountdown(n)
      onProgress?.(`Waiting for restart… ${n}s`)
      await sleep(1000)
    }
    setUpdatingCountdown(0)
    onProgress?.('Checking agent version…')

    const deadline = Date.now() + AGENT_UPDATE_POLL_MS
    let lastVer = ''
    while (Date.now() < deadline) {
      const snap = await load({ refresh: true })
      const s = snap.items.find((x) => x.id === serverId)
      lastVer = (s?.agent_version || '').trim()
      const compareTo = want || snap.channelLatest.trim().replace(/^v/i, '')
      if (lastVer && compareTo && !agentVersionOutdated(lastVer, compareTo)) {
        return { version: lastVer, visible: true }
      }
      onProgress?.(`Checking agent version… (${lastVer || '—'})`)
      await sleep(2000)
    }
    return { version: lastVer, visible: false }
  }

  async function confirmAgentUpdate() {
    if (!updateTarget) return
    const id = updateTarget.id
    const prev = updateTarget
    setUpdatingId(id)
    setUpdatingCountdown(0)
    try {
      const res = await api.agentUpdate({ force: false }, { server: id })
      if (res.ok === false) {
        throw new Error(res.message || res.error || 'update failed')
      }
      const expected = (res.version || res.remote_version || channelLatest || '').trim()
      if (!res.updated) {
        notifications.show({
          color: 'teal',
          message: res.message || `Agent already on ${res.version || prev.agent_version || '?'}`,
        })
        await load({ refresh: true })
        setUpdateTarget(null)
        return
      }
      const seen = await waitAgentVersionVisible(id, expected)
      notifications.show({
        color: seen.visible ? 'teal' : 'yellow',
        message: seen.visible
          ? `Agent updated → ${seen.version || expected}`
          : `Agent binaries installed → ${expected || '?'}; UI still shows ${seen.version || prev.agent_version || '?'}. Refresh shortly.`,
        autoClose: seen.visible ? 4000 : 8000,
      })
      setUpdateTarget(null)
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setUpdatingId(null)
      setUpdatingCountdown(0)
    }
  }

  async function confirmUpdateAllAgents() {
    const latest = channelLatest.trim()
    const targets = outdatedServers
    if (targets.length === 0) {
      notifications.show({ color: 'yellow', message: 'No servers behind CDN' })
      return
    }
    setUpdateAllBusy(true)
    setUpdateAllProgress('')
    let ok = 0
    let fail = 0
    let pending = 0
    const errors: string[] = []
    for (let i = 0; i < targets.length; i++) {
      const s = targets[i]
      setUpdatingId(s.id)
      setUpdatingCountdown(0)
      const label = `${i + 1}/${targets.length} · ${s.name || s.id}`
      setUpdateAllProgress(label)
      try {
        const res = await api.agentUpdate({ force: false }, { server: s.id })
        if (res.ok === false) throw new Error(res.error || res.message || 'update failed')
        const expected = (res.version || res.remote_version || latest || '').trim()
        if (res.updated) {
          const seen = await waitAgentVersionVisible(s.id, expected, (msg) => {
            setUpdateAllProgress(`${label} · ${msg}`)
          })
          if (seen.visible) ok++
          else pending++
        } else {
          ok++
        }
      } catch (e) {
        fail++
        errors.push(`${s.name || s.id}: ${String((e as Error).message || e)}`)
      }
    }
    setUpdatingId(null)
    setUpdatingCountdown(0)
    setUpdateAllBusy(false)
    setUpdateAllOpen(false)
    setUpdateAllProgress('')
    const color = fail === 0 && pending === 0 ? 'teal' : ok > 0 || pending > 0 ? 'yellow' : 'red'
    notifications.show({
      color,
      message:
        fail === 0 && pending === 0
          ? `Updated ${ok} server agent${ok === 1 ? '' : 's'} → ${latest || 'CDN'}`
          : `Agents: ${ok} ok${pending ? `, ${pending} restarting` : ''}${fail ? `, ${fail} failed` : ''}${errors[0] ? ` · ${errors[0]}` : ''}`,
      autoClose: fail === 0 && pending === 0 ? 4000 : 8000,
    })
    await load({ refresh: true })
  }

  /**
   * "Remove all nodes from panel" — for a server whose nodes are stuck (e.g.
   * host agent errors on the normal wipe/agents Remove) drop every node row
   * for this server from panel SQLite only (mode: 'panel'), never touching
   * the host. Lets the operator then delete the server itself.
   */
  async function purgeServerNodes() {
    if (!removeTarget) return
    setPurgingNodes(true)
    try {
      const res = await api.workloadsList()
      const targets = (res.items || []).filter((w) => w.server_id === removeTarget.id)
      if (targets.length === 0) {
        await load({ refresh: true })
        return
      }
      let ok = 0
      const failed: string[] = []
      for (const w of targets) {
        try {
          const r = await api.workloadsRemove({ id: w.id, mode: 'panel' })
          if (r.ok === false) throw new Error(r.message || r.error || 'failed')
          ok++
        } catch (e) {
          failed.push(`${w.network}/${w.env}: ${String((e as Error).message || e)}`)
        }
      }
      if (ok > 0) {
        notifications.show({
          color: 'teal',
          message: `Removed ${ok} node${ok === 1 ? '' : 's'} from panel (host untouched)`,
        })
      }
      if (failed.length > 0) {
        notifications.show({ color: 'red', message: failed.join('; ') })
      }
      const snap = await load({ refresh: true })
      const fresh = snap.items.find((x) => x.id === removeTarget.id)
      setRemoveTarget(fresh || null)
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setPurgingNodes(false)
    }
  }

  async function confirmRemove() {
    if (!removeTarget) return
    if ((removeTarget.nodes_count ?? 0) > 0 || removeTarget.can_delete === false) {
      notifications.show({
        color: 'yellow',
        message: `Remove ${removeTarget.nodes_count ?? 0} node(s) first, then delete the server.`,
      })
      setRemoveTarget(null)
      return
    }
    setRemoving(true)
    try {
      await api.registryDelete(removeTarget.id)
      notifications.show({
        color: 'teal',
        message: `Removing ${removeTarget.name || removeTarget.id}…`,
      })
      setRemoveTarget(null)
      void load()
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setRemoving(false)
    }
  }

  return (
    <AppChrome
      block="servers"
      title="Servers"
      right={
        <Group gap="xs">
          <Button
            size="xs"
            variant="default"
            leftSection={<IconRefresh size={14} />}
            loading={refreshing}
            disabled={updateAllBusy}
            onClick={() => void load({ refresh: true })}
          >
            Refresh
          </Button>
          <Tooltip
            label={
              outdatedServers.length > 0
                ? `Update ${outdatedServers.length} server${outdatedServers.length === 1 ? '' : 's'} behind CDN ${channelLatest || ''}`
                : channelLatest
                  ? `All servers already on ${channelLatest}`
                  : 'Fetch CDN channel first (Refresh)'
            }
          >
            <Button
              size="xs"
              variant="light"
              color="orange"
              leftSection={<IconDownload size={14} />}
              loading={updateAllBusy}
              disabled={
                outdatedServers.length === 0 || !channelLatest || !!updatingId
              }
              onClick={() => setUpdateAllOpen(true)}
            >
              Update all agents
              {outdatedServers.length > 0 ? ` (${outdatedServers.length})` : ''}
            </Button>
          </Tooltip>
          <Button
            size="xs"
            color="teal"
            leftSection={<IconPlus size={14} />}
            disabled={updateAllBusy}
            onClick={() => setAddOpen(true)}
          >
            Add server
          </Button>
        </Group>
      }
    >
      <Stack gap="md" mt="md" {...blockProps('servers.content')}>
        {loading && items.length === 0 ? (
          <Center mih={240}>
            <Stack align="center" gap="sm">
              <Loader color="teal" />
              <Text c="dimmed">Loading servers…</Text>
            </Stack>
          </Center>
        ) : (
          <>
        {error && (
          <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Servers error" {...blockProps('servers.error')}>
            {error}
          </Alert>
        )}

        {channelLatest ? (
          <Text size="xs" c="dimmed">
            Install channel latest:{' '}
            <Text span fw={600} className="mono" c="teal.4">
              {channelLatest}
            </Text>{' '}
            ·{' '}
            <Text span className="mono">
              /api/agent/channel
            </Text>{' '}
            (panel agent version)
          </Text>
        ) : null}

        {items.length === 0 ? (
          <Card {...blockProps('servers.empty')}>
            <Stack gap="sm">
              <Text fw={600}>No servers registered</Text>
              <Text size="sm" c="dimmed">
                Install a host agent, enter its IP and AGENT_API_TOKEN, pass the connection check,
                then add. Chain nodes are managed under Nodes.
              </Text>
              <Group>
                <Button color="teal" leftSection={<IconPlus size={16} />} onClick={() => setAddOpen(true)}>
                  Add server
                </Button>
                <Button variant="default" onClick={() => navigate({ name: 'nodes' })}>
                  View nodes
                </Button>
              </Group>
            </Stack>
          </Card>
        ) : (
          <SimpleGrid cols={{ base: 1, sm: 2, lg: 2, xl: 3 }} spacing="md" {...blockProps('servers.list')}>
            {items.map((s) => {
              const plat = platformLabel(s)
              const m = s.metrics
              const removingHost = s.remove_status === 'removing' || s.metrics_status === 'removing'
              const st = removingHost ? 'removing' : s.metrics_status || 'unknown'
              const nodes = s.nodes_count ?? 0
              const agentVer = s.agent_version || ''
              const latestVer = channelLatest || s.latest_agent_version || ''
              const needsUpdate =
                !!latestVer &&
                (!!s.agent_update_available || agentVersionOutdated(agentVer, latestVer))
              const agentCurrent = !!agentVer && !!latestVer && !needsUpdate
              const agentColor = !agentVer ? 'dimmed' : needsUpdate ? 'orange' : 'teal'
              const updating = updatingId === s.id
              return (
                <Card key={s.id} className="env-card server-card" {...blockProps(`servers.card.${s.id}`)}>
                  <Group
                    justify="space-between"
                    align="flex-start"
                    wrap="nowrap"
                    gap="sm"
                    mb="sm"
                    className="server-card__head"
                  >
                    <Group gap="sm" align="flex-start" wrap="nowrap" style={{ minWidth: 0, flex: 1 }}>
                      <ThemeIcon color="teal" variant="light" size="lg" style={{ flexShrink: 0 }}>
                        <IconServer size={18} />
                      </ThemeIcon>
                      <div style={{ minWidth: 0, flex: 1 }}>
                        <Text fw={700} className="server-card__name" title={s.name || s.id}>
                          {displayServerName(s)}
                        </Text>
                        <Text size="xs" c="dimmed" className="mono server-card__id">
                          {s.id}
                        </Text>
                      </div>
                    </Group>
                    <Badge
                      color={statusColor(st)}
                      variant="light"
                      style={{ flexShrink: 0, textTransform: 'uppercase' }}
                    >
                      {st}
                    </Badge>
                  </Group>

                  <Stack gap={8} style={{ flex: 1 }} className="server-card__body">
                    {/* Always 2×2 — placeholders keep card height stable across poll ticks. */}
                    <SimpleGrid cols={2} spacing="xs" verticalSpacing={8} className="server-card__metrics">
                      <MetricCell
                        label="CPU"
                        value={hasMetrics(m) ? fmtPct(displayCPUPct(m)) : '—'}
                        level={hasMetrics(m) ? metricLevel(displayCPUPct(m)) : undefined}
                        hint={
                          hasMetrics(m)
                            ? 'CPU busy from /proc/stat (≈ mpstat 100−%idle)'
                            : 'No metrics yet'
                        }
                      />
                      <MetricCell
                        label="RAM"
                        value={hasMetrics(m) ? fmtMem(m) : '—'}
                        level={hasMetrics(m) ? metricLevel(memUsedPct(m)) : undefined}
                        hint={hasMetrics(m) ? 'Memory used / total' : 'No metrics yet'}
                      />
                      <MetricCell
                        label="Disk"
                        value={hasMetrics(m) ? fmtDisk(m) : '—'}
                        level={hasMetrics(m) ? metricLevel(diskUsedPct(m)) : undefined}
                        hint={hasMetrics(m) ? diskHint(m) : 'No metrics yet'}
                      />
                      <MetricCell
                        label="Load avg"
                        value={hasMetrics(m) ? fmtLoad(m?.load_1) : '—'}
                        level={hasMetrics(m) ? metricLevel(loadPressurePct(m)) : undefined}
                        hint={
                          hasMetrics(m)
                            ? '1-minute system load average (Linux loadavg)'
                            : 'No metrics yet'
                        }
                      />
                    </SimpleGrid>

                    <div className="server-card__os">
                      <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.4 }}>
                        OS
                      </Text>
                      <Text size="sm" fw={600} className="mono">
                        {plat || '—'}
                      </Text>
                    </div>

                    <Group
                      justify="space-between"
                      align="center"
                      wrap="nowrap"
                      gap="xs"
                      className="server-card__agent"
                    >
                      <div style={{ minWidth: 0 }}>
                        <Text size="xs" c="dimmed" tt="uppercase" style={{ letterSpacing: 0.4 }}>
                          Agent
                        </Text>
                        <Group gap={6} align="center" wrap="nowrap">
                          <Text
                            size="sm"
                            fw={600}
                            className="mono"
                            c={agentColor}
                            style={{ flexShrink: 0 }}
                            title={
                              needsUpdate
                                ? `Installed ${agentVer} — CDN latest ${latestVer}`
                                : agentCurrent
                                  ? `Up to date with CDN ${latestVer}`
                                  : 'Installed agent version'
                            }
                          >
                            {agentVer || '—'}
                          </Text>
                          {agentCurrent ? (
                            <Badge color="teal" variant="light" size="sm">
                              latest
                            </Badge>
                          ) : null}
                          <Badge
                            color="orange"
                            variant="light"
                            size="sm"
                            className="server-card__upd-badge"
                            style={{ visibility: needsUpdate && latestVer ? 'visible' : 'hidden' }}
                          >
                            → {latestVer || '0.0.0'}
                          </Badge>
                        </Group>
                      </div>
                      <Tooltip
                        label={
                          updating && updatingCountdown > 0
                            ? `Check version in ${updatingCountdown}s`
                            : updating
                              ? 'Checking agent version…'
                              : needsUpdate
                                ? `Update agent ${agentVer || '?'} → ${latestVer || 'CDN'}`
                                : agentVer
                                  ? `Agent up to date (${agentVer})`
                                  : 'Agent version unknown'
                        }
                      >
                        <span>
                          <ActionIcon
                            color={needsUpdate || updating ? 'orange' : 'gray'}
                            variant="light"
                            size="lg"
                            loading={updating && updatingCountdown === 0}
                            disabled={removingHost || (!needsUpdate && !updating) || (!!updatingId && !updating)}
                            aria-label="Update agent"
                            onClick={() => needsUpdate && !updatingId && setUpdateTarget(s)}
                          >
                            {updating && updatingCountdown > 0 ? (
                              <Text size="xs" fw={700} className="mono">
                                {updatingCountdown}
                              </Text>
                            ) : (
                              <IconDownload size={16} />
                            )}
                          </ActionIcon>
                        </span>
                      </Tooltip>
                    </Group>

                    <Text size="xs" c="dimmed" className="server-card__nodes">
                      Nodes on this server: {nodes}
                    </Text>
                    <CopyMaskedUrl
                      className="server-card__url"
                      label="Agent URL"
                      url={s.agent_url || ''}
                      copyMessage="Agent URL copied"
                    />
                  </Stack>

                  <Group className="env-card__actions" gap="xs" mt="sm" wrap="nowrap">
                    <Button size="xs" variant="light" onClick={() => navigate({ name: 'nodes' })}>
                      Nodes
                    </Button>
                    <Tooltip label="Host logs (install / snapshot / errors / agents)">
                      <ActionIcon
                        size="md"
                        variant="light"
                        color="gray"
                        aria-label="Server host logs"
                        onClick={() => setLogsTarget(s)}
                      >
                        <IconFileText size={14} />
                      </ActionIcon>
                    </Tooltip>
                    <Tooltip label="Scan host for nodes already installed but not in the panel">
                      <ActionIcon
                        size="md"
                        variant="light"
                        color="gray"
                        aria-label="Scan for existing nodes"
                        disabled={removingHost}
                        onClick={() => setDiscoverTarget(s)}
                      >
                        <IconSearch size={14} />
                      </ActionIcon>
                    </Tooltip>
                    <Button
                      size="xs"
                      variant="light"
                      leftSection={<IconPencil size={12} />}
                      disabled={removingHost}
                      onClick={() => openEdit(s)}
                    >
                      Edit
                    </Button>
                    <Button
                      size="xs"
                      variant="subtle"
                      color="red"
                      leftSection={<IconTrash size={12} />}
                      disabled={removingHost}
                      onClick={() => setRemoveTarget(s)}
                    >
                      {removingHost ? 'Removing…' : 'Remove'}
                    </Button>
                  </Group>
                </Card>
              )
            })}
          </SimpleGrid>
        )}
          </>
        )}
      </Stack>

      <Modal
        {...blockProps('modal.remove-server')}
        opened={!!removeTarget}
        onClose={() => (!removing ? setRemoveTarget(null) : undefined)}
        title="Remove server?"
        centered
      >
        <Stack gap="md">
          {(removeTarget?.nodes_count ?? 0) > 0 ? (
            <>
              <Alert color="yellow" icon={<IconAlertTriangle size={16} />} title="Delete nodes first">
                Server{' '}
                <Text span fw={700}>
                  {removeTarget?.name || removeTarget?.id}
                </Text>{' '}
                still has {removeTarget?.nodes_count} node(s). Remove those nodes under Nodes, then
                you can delete the server.
              </Alert>
              <Alert color="gray" title="Stuck nodes? Clean the panel database only">
                <Stack gap="xs">
                  <Text size="sm">
                    If normal Remove errors out on this host (agent unreachable, broken discovered
                    node, …), drop all {removeTarget?.nodes_count} node row{removeTarget?.nodes_count === 1 ? '' : 's'} from
                    the panel database only. The host agents, units and datadirs are not touched —
                    use this only to unblock deleting the server entry itself.
                  </Text>
                  <Button
                    size="xs"
                    variant="light"
                    color="red"
                    leftSection={<IconTrash size={14} />}
                    loading={purgingNodes}
                    onClick={() => void purgeServerNodes()}
                  >
                    Remove all {removeTarget?.nodes_count} node{removeTarget?.nodes_count === 1 ? '' : 's'} from panel
                  </Button>
                </Stack>
              </Alert>
              <Group justify="flex-end">
                <Button variant="default" disabled={purgingNodes} onClick={() => setRemoveTarget(null)}>
                  Cancel
                </Button>
                <Button
                  color="teal"
                  disabled={purgingNodes}
                  onClick={() => {
                    setRemoveTarget(null)
                    navigate({ name: 'nodes' })
                  }}
                >
                  Go to Nodes
                </Button>
              </Group>
            </>
          ) : (
            <>
              <Text size="sm" c="dimmed">
                No nodes attached — safe to remove from the panel.
              </Text>
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
                  onClick={() => void confirmRemove()}
                >
                  Remove server
                </Button>
              </Group>
            </>
          )}
        </Stack>
      </Modal>

      <Modal
        {...blockProps('modal.update-server-agent')}
        opened={!!updateTarget}
        onClose={() => (!updatingId ? setUpdateTarget(null) : undefined)}
        title="Update host agent?"
        centered
      >
        <Stack gap="md">
          <Alert color="yellow" icon={<IconAlertTriangle size={16} />}>
            Downloads <strong>api-agent + system-agent</strong> from this host's install origin
            (client-sync or CDN, whatever it was installed from) and restarts their systemd units.
            Brief disconnect possible. UI waits {AGENT_UPDATE_SETTLE_SEC}s, then checks the new
            version.
          </Alert>
          <Text size="sm">
            <Text span fw={700}>
              {updateTarget?.name || updateTarget?.id}
            </Text>
            :{' '}
            <Code className="mono">{updateTarget?.agent_version || '?'}</Code>
            {' → '}
            <Code className="mono">
              {channelLatest || updateTarget?.latest_agent_version || 'CDN latest'}
            </Code>
          </Text>
          {updatingId === updateTarget?.id && updatingCountdown > 0 ? (
            <Text size="sm" c="dimmed">
              Waiting for restart… <Code className="mono">{updatingCountdown}s</Code>
            </Text>
          ) : null}
          {updatingId === updateTarget?.id && updatingCountdown === 0 ? (
            <Text size="sm" c="dimmed">
              Checking agent version…
            </Text>
          ) : null}
          <Group justify="flex-end">
            <Button
              variant="default"
              disabled={!!updatingId}
              onClick={() => setUpdateTarget(null)}
            >
              Cancel
            </Button>
            <Button
              color="teal"
              leftSection={
                updatingCountdown > 0 || updatingId === updateTarget?.id ? undefined : (
                  <IconDownload size={14} />
                )
              }
              loading={!!updatingId && updatingCountdown === 0}
              disabled={!!updatingId}
              onClick={() => void confirmAgentUpdate()}
            >
              {updatingCountdown > 0
                ? `Check version in ${updatingCountdown}s`
                : updatingId === updateTarget?.id
                  ? 'Checking version…'
                  : 'Confirm update + restart'}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        {...blockProps('modal.update-all-agents')}
        opened={updateAllOpen}
        onClose={() => (!updateAllBusy ? setUpdateAllOpen(false) : undefined)}
        title="Update outdated agents?"
        centered
      >
        <Stack gap="md">
          <Alert color="yellow" icon={<IconAlertTriangle size={16} />}>
            Only servers with agent version <strong>below</strong> CDN{' '}
            <Code className="mono">{channelLatest || '?'}</Code>. Already-latest hosts are
            skipped. One server at a time.
          </Alert>
          <Stack gap={4}>
            <Text size="sm" fw={600}>
              Will update ({outdatedServers.length}):
            </Text>
            {outdatedServers.map((s) => (
              <Text key={s.id} size="sm" className="mono">
                {s.name || s.id}: {s.agent_version || '?'} → {channelLatest}
              </Text>
            ))}
          </Stack>
          {updateAllBusy && updateAllProgress ? (
            <Text size="sm" c="dimmed">
              Updating… {updateAllProgress}
            </Text>
          ) : null}
          <Group justify="flex-end">
            <Button
              variant="default"
              disabled={updateAllBusy}
              onClick={() => setUpdateAllOpen(false)}
            >
              Cancel
            </Button>
            <Button
              color="orange"
              loading={updateAllBusy && updatingCountdown === 0}
              leftSection={
                updateAllBusy && updatingCountdown > 0 ? undefined : <IconDownload size={14} />
              }
              disabled={outdatedServers.length === 0 || updateAllBusy}
              onClick={() => void confirmUpdateAllAgents()}
            >
              {updateAllBusy && updatingCountdown > 0
                ? `Check version in ${updatingCountdown}s`
                : updateAllBusy
                  ? 'Updating…'
                  : `Update ${outdatedServers.length} behind`}
            </Button>
          </Group>
        </Stack>
      </Modal>

      <Modal
        {...blockProps('modal.edit-server')}
        opened={!!editTarget}
        onClose={() => (!editSaving ? setEditTarget(null) : undefined)}
        title={`Edit server · ${editTarget?.name || editTarget?.id || ''}`}
        centered
      >
        <Stack gap="md">
          <TextInput
            label="Name"
            value={editName}
            onChange={(e) => setEditName(e.currentTarget.value)}
          />
          <TextInput
            label="Agent URL"
            description="rpcnode-agent HTTP — http://<host>:48990"
            className="mono"
            value={editURL}
            onChange={(e) => setEditURL(e.currentTarget.value)}
            placeholder="http://203.0.113.10:48990"
          />
          <PasswordInput
            label="Agent key (optional)"
            description="Leave empty to keep the saved key"
            value={editKey}
            onChange={(e) => setEditKey(e.currentTarget.value)}
            placeholder="AGENT_API_TOKEN"
          />
          <Group justify="flex-end">
            <Button variant="default" disabled={editSaving} onClick={() => setEditTarget(null)}>
              Cancel
            </Button>
            <Button color="teal" loading={editSaving} onClick={() => void saveEdit()}>
              Save & check
            </Button>
          </Group>
        </Stack>
      </Modal>

      <ServerLogsModal
        opened={!!logsTarget}
        onClose={() => setLogsTarget(null)}
        serverId={logsTarget?.id || ''}
        serverName={logsTarget ? displayServerName(logsTarget) : ''}
      />

      <AddServerModal
        opened={addOpen}
        onClose={() => setAddOpen(false)}
        onAdded={() => void load({ refresh: true })}
      />

      <DiscoverNodesModal
        opened={!!discoverTarget}
        onClose={() => setDiscoverTarget(null)}
        serverId={discoverTarget?.id || ''}
        serverName={discoverTarget ? displayServerName(discoverTarget) : ''}
        onImported={() => void load({ refresh: true })}
      />
    </AppChrome>
  )
}
