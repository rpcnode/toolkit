import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  Accordion,
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Card,
  Group,
  Loader,
  Modal,
  PasswordInput,
  Select,
  Stack,
  Switch,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core'
import {
  IconAlertTriangle,
  IconBook2,
  IconCheck,
  IconCopy,
  IconDeviceFloppy,
  IconLock,
  IconPlus,
  IconRefresh,
  IconTrash,
} from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import {
  api,
  type AgentTarget,
  type DiskRolePlacement,
  type MultiDiskLayoutPlan,
  type NodeConfigDocument,
  type NodeConfigField,
  type NodeConfigResponse,
  type ProvisionDiskLayout,
} from '../api'
import { copyText } from '../lib/copyText'
import { officialDocsUrl } from '../lib/networkDocs'

type KvRow = {
  key: string
  value: string
  label?: string
  help?: string
  type?: string
  group?: string
  protected?: boolean
  options?: string[]
  /** Present in original file / schema (not only newly added in UI). */
  known?: boolean
}

function fieldsEditable(format: string): boolean {
  return ['ini', 'cfg', 'env', 'toml', 'hocon'].includes((format || '').toLowerCase())
}

/** Normalize config key for secret / identity matching (strip leading --). */
function normalizeConfigKey(key: string): string {
  return key.trim().toLowerCase().replace(/^--+/, '')
}

/** Secrets: masked PasswordInput + copy (rpcpassword, jwt, cookie, *_token, …). */
function isSecretConfigKey(key: string): boolean {
  const k = normalizeConfigKey(key)
  if (!k) return false
  const exact = new Set([
    'rpcpassword',
    'rpcauth',
    'password',
    'passphrase',
    'jwt',
    'base_node_l2_engine_auth_raw',
    'base_node_l2_engine_auth',
    'cookie',
    'rpc.cookie',
    'cookiefile',
    'cookie_file',
    'cookie-file',
  ])
  if (exact.has(k)) return true
  if (/(^|[_.-])(password|passphrase|secret|token|jwt)([_.-]|$)/.test(k)) return true
  if (k.endsWith('password') || k.endsWith('passphrase') || k.endsWith('secret') || k.endsWith('token')) {
    return true
  }
  if (k.includes('cookie') || k.includes('jwt')) return true
  return false
}

/** Identity / auth values worth copying (rpcuser + all secrets). */
function isCopyableConfigKey(key: string): boolean {
  if (isSecretConfigKey(key)) return true
  const k = normalizeConfigKey(key)
  return k === 'rpcuser' || k === 'username' || k === 'user' || k === 'rpc.user'
}

const monoInputStyles = {
  input: { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
} as const

function copyConfigValue(value: string, label?: string) {
  const text = value ?? ''
  if (!text) {
    notifications.show({ color: 'yellow', message: 'Nothing to copy', autoClose: 1500 })
    return
  }
  void copyText(text)
    .then(() => {
      notifications.show({
        color: 'teal',
        message: label ? `${label} copied` : 'Copied',
        autoClose: 1500,
      })
    })
    .catch(() => {
      notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
    })
}

function CopyValueButton({ value, label }: { value: string; label?: string }) {
  const [copied, setCopied] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(
    () => () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    },
    [],
  )
  return (
    <Tooltip label={copied ? 'Copied' : 'Copy value'}>
      <ActionIcon
        size="sm"
        variant="subtle"
        color={copied ? 'teal' : 'gray'}
        aria-label={label ? `Copy ${label}` : 'Copy value'}
        onClick={() => {
          copyConfigValue(value, label)
          setCopied(true)
          if (timerRef.current) clearTimeout(timerRef.current)
          timerRef.current = setTimeout(() => setCopied(false), 1500)
        }}
      >
        {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
      </ActionIcon>
    </Tooltip>
  )
}

/** Mirror system-agent isPortLikeKey — not naive /port/i (blocks support/export/…). */
function isPortLikeKey(key: string): boolean {
  const k = key.toLowerCase().trim()
  if (!k) return false
  if (k.includes('password') || k.includes('passport') || k.includes('opportunity')) return false
  if (k === 'port' || k.startsWith('port_') || k.startsWith('port.')) return true
  if (k.endsWith('port') || k.includes('_port') || k.includes('.port') || k.includes('-port')) return true
  if (k.startsWith('zmq')) return true
  if (k.includes('endpoint')) return true
  if (k.endsWith('bind') || k.includes('rpcbind') || k.includes('whitebind')) return true
  if (k.includes('listen') && (k.includes('addr') || k.includes('port'))) return true
  if (k === 'httphost' || k === 'wshost' || k === 'authaddr' || k === 'listenaddr') return true
  return false
}

/** Mirror system-agent isDataDirLikeKey (dbcache etc. stay allowed). */
function isDataDirLikeKey(key: string): boolean {
  let k = key.toLowerCase().trim()
  if (k.startsWith('--')) k = k.slice(2)
  if (!k) return false
  if (k.includes('password') || k.includes('cache') || k.includes('timeout')) return false
  const exact = new Set([
    'datadir',
    'data_dir',
    'data-dir',
    'data.dir',
    'dbpath',
    'db_path',
    'db-path',
    'db.path',
    'blocksdir',
    'blocks_dir',
    'blocks-dir',
    'walletdir',
    'wallet_dir',
    'wallet-dir',
    'wallet',
    'ledger-path',
    'ledger_path',
    'ledgerpath',
    'ledger.path',
    'accounts-path',
    'accounts_path',
    'accountspath',
    'accounts.path',
    'chaindata',
    'chain_data',
    'chain-data',
    'dbhome',
    'db_home',
    'db-home',
  ])
  if (exact.has(k)) return true
  if (
    k.endsWith('datadir') ||
    k.endsWith('data_dir') ||
    k.endsWith('data-dir') ||
    k.endsWith('data.dir')
  ) {
    return true
  }
  if (k.endsWith('dbpath') || k.endsWith('db_path') || k.endsWith('db-path') || k.endsWith('db.path')) {
    return true
  }
  if (k.endsWith('blocksdir') || k.endsWith('walletdir')) return true
  if (k.includes('ledger') && k.includes('path')) return true
  if (k.includes('accounts') && k.includes('path')) return true
  if (k.includes('chain') && k.includes('data') && !k.includes('database')) return true
  return false
}

/** Agent wiring auth keys — locked like mergeProtectedKeys auth set. */
function isAuthLikeKey(key: string): boolean {
  const k = key.toLowerCase().trim().replace(/^--/, '')
  return (
    k === 'rpcuser' ||
    k === 'rpcpassword' ||
    k === 'rpcauth' ||
    k === 'rpcallowip' ||
    k.endsWith('_auth_raw') ||
    k.endsWith('_jwt') ||
    k === 'jwtsecret' ||
    k === 'jwt_secret'
  )
}

/** Human reason if key cannot be added; null if allowed. */
function lockedAddKeyReason(key: string): string | null {
  if (isPortLikeKey(key)) {
    return 'Port / bind / endpoint keys cannot be added — catalog / Confirm ports owns them.'
  }
  if (isDataDirLikeKey(key)) {
    return 'datadir / ledger-path and similar keys cannot be added — provision owns chain data paths.'
  }
  if (isAuthLikeKey(key)) {
    return 'Auth keys (rpcuser / rpcpassword / JWT) cannot be added — agent / Go proxy owns them.'
  }
  return null
}

function parseLooseKV(content: string): Array<{ key: string; value: string }> {
  const out: Array<{ key: string; value: string }> = []
  const seen = new Set<string>()
  for (const line of content.split('\n')) {
    const trim = line.trim()
    if (!trim || trim.startsWith('#') || trim.startsWith(';') || trim.startsWith('//')) continue
    if (trim.startsWith('[') && trim.endsWith(']')) continue
    if (!trim.includes('=')) continue
    const i = trim.indexOf('=')
    const key = trim.slice(0, i).trim()
    let value = trim.slice(i + 1).trim()
    value = value.replace(/^["']|["']$/g, '')
    if (!key) continue
    const lk = key.toLowerCase()
    if (seen.has(lk)) continue
    seen.add(lk)
    out.push({ key, value })
  }
  return out
}

function serializeLooseKV(
  original: string,
  rows: KvRow[],
): string {
  // Preserve comments/sections; update or append key=value lines.
  const byLower = new Map<string, string>()
  for (const r of rows) {
    const k = r.key.trim()
    if (!k) continue
    byLower.set(k.toLowerCase(), r.value)
  }
  const seen = new Set<string>()
  const lines = original.split('\n')
  const next = lines.map((line) => {
    const trim = line.trim()
    if (!trim || trim.startsWith('#') || trim.startsWith(';') || trim.startsWith('[')) return line
    if (!trim.includes('=')) return line
    const k = trim.split('=', 2)[0].trim()
    const lk = k.toLowerCase()
    if (!byLower.has(lk)) {
      // Key removed in UI → drop line
      return null
    }
    const v = byLower.get(lk) ?? ''
    seen.add(lk)
    const indent = line.match(/^\s*/)?.[0] || ''
    return `${indent}${k}=${v}`
  })
  const kept = next.filter((l): l is string => l != null)
  let out = kept.join('\n')
  for (const r of rows) {
    const k = r.key.trim()
    if (!k) continue
    const lk = k.toLowerCase()
    if (seen.has(lk)) continue
    if (out && !out.endsWith('\n')) out += '\n'
    out += `${k}=${r.value}\n`
    seen.add(lk)
  }
  return out
}

function buildRows(content: string, fields?: NodeConfigField[]): KvRow[] {
  const meta = new Map<string, NodeConfigField>()
  for (const f of fields || []) meta.set(f.key.toLowerCase(), f)
  const parsed = parseLooseKV(content)
  const seen = new Set<string>()
  const rows: KvRow[] = []
  for (const p of parsed) {
    const m = meta.get(p.key.toLowerCase())
    seen.add(p.key.toLowerCase())
    rows.push({
      key: p.key,
      value: p.value,
      label: m?.label,
      help: m?.help,
      type: m?.type,
      group: m?.group || 'config',
      protected: !!m?.protected,
      options: m?.options,
      known: true,
    })
  }
  // Curated keys missing from file (show empty / default from schema value)
  for (const f of fields || []) {
    const lk = f.key.toLowerCase()
    if (seen.has(lk)) continue
    if (f.protected) continue // don't invent locked keys
    rows.push({
      key: f.key,
      value: f.value || '',
      label: f.label,
      help: f.help,
      type: f.type,
      group: f.group || 'config',
      protected: !!f.protected,
      options: f.options,
      known: false,
    })
  }
  // Locked keys last within group for clarity — sort group then lock then key
  rows.sort((a, b) => {
    const ga = a.group || 'config'
    const gb = b.group || 'config'
    if (ga !== gb) return ga.localeCompare(gb)
    if (!!a.protected !== !!b.protected) return a.protected ? 1 : -1
    return a.key.localeCompare(b.key)
  })
  return rows
}

type Props = {
  target: AgentTarget
  network: string
  env: string
  /** Panel node UUID — loads persisted disk_layout from SQLite. */
  nodeId?: string
  /** When false, nothing renders (card) / modal stays closed externally. */
  enabled: boolean
  onApplied?: () => void
  /** Default card on the page. Prefer modal from the node toolbar. */
  mode?: 'card' | 'modal'
  opened?: boolean
  onClose?: () => void
}

function diskLayoutRoles(
  layout: ProvisionDiskLayout | MultiDiskLayoutPlan | null | undefined,
): DiskRolePlacement[] {
  if (!layout) return []
  if (Array.isArray(layout.roles) && layout.roles.length) {
    return layout.roles.filter((r) => r?.id)
  }
  const map = layout.roles_map || (layout.roles as Record<string, { dir?: string; mount?: string }> | undefined)
  if (map && typeof map === 'object' && !Array.isArray(map)) {
    return Object.entries(map).map(([id, v]) => ({
      id,
      dir: v?.dir,
      mount: v?.mount,
    }))
  }
  const legacy: DiskRolePlacement[] = []
  const push = (id: string, dir?: string, mount?: string) => {
    if (dir || mount) legacy.push({ id, dir, mount })
  }
  push('ledger', layout.ledger_dir, layout.ledger_mount)
  push('accounts', layout.accounts_dir, layout.accounts_mount)
  push('snapshots', layout.snapshots_dir, layout.snapshots_mount)
  push('state', layout.state_dir, layout.state_mount)
  push('index', layout.index_dir, layout.index_mount)
  return legacy
}

/** Stable identity for AgentTarget — parent often passes a fresh `{ node }` object each render. */
function agentTargetKey(t: AgentTarget): string {
  return `${t.node || ''}\0${t.server || ''}\0${t.env || ''}\0${t.network || ''}`
}

export function NodeConfigPanel({
  target,
  network,
  env,
  nodeId,
  enabled,
  onApplied,
  mode = 'card',
  opened = false,
  onClose,
}: Props) {
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [data, setData] = useState<NodeConfigResponse | null>(null)
  const [activeId, setActiveId] = useState('')
  const [drafts, setDrafts] = useState<Record<string, string>>({})
  const [confirmOpen, setConfirmOpen] = useState(false)
  const [filter, setFilter] = useState('')
  const [newKey, setNewKey] = useState('')
  const [newVal, setNewVal] = useState('')
  const [diskLayout, setDiskLayout] = useState<ProvisionDiskLayout | MultiDiskLayoutPlan | null>(null)

  const active = mode === 'modal' ? enabled && opened : enabled
  const targetKey = agentTargetKey(target)
  const targetRef = useRef(target)
  targetRef.current = target
  /** Tracks prior `active` so modal loads only on false→true (not every parent re-render). */
  const wasActiveRef = useRef(false)

  const load = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const res = await api.nodeConfig(targetRef.current)
      setData(res)
      const docs = res.documents || []
      const next: Record<string, string> = {}
      for (const d of docs) next[d.id] = d.content || ''
      setDrafts(next)
      setActiveId((prev) => (prev && docs.some((d) => d.id === prev) ? prev : docs[0]?.id || ''))
      if (nodeId) {
        try {
          const dl = await api.workloadsDiskLayout(nodeId)
          setDiskLayout(dl.disk_layout || null)
        } catch {
          setDiskLayout(null)
        }
      } else {
        setDiskLayout(null)
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setData(null)
    } finally {
      setLoading(false)
    }
  }, [targetKey, nodeId])

  useEffect(() => {
    if (!active) {
      wasActiveRef.current = false
      return
    }
    const becameActive = !wasActiveRef.current
    wasActiveRef.current = true
    // Modal: load once when opened; ignore parent identity churn while open.
    // Card: also reload when target/network/env identity changes.
    if (mode === 'modal' && !becameActive) return
    void load()
  }, [active, mode, load, targetKey, network, env])

  const doc = useMemo(
    () => (data?.documents || []).find((d) => d.id === activeId) || null,
    [data, activeId],
  )
  // Same fallback as apply/add — never treat missing draft as "" while writers use doc.content.
  const draft = doc ? (drafts[doc.id] ?? doc.content ?? '') : ''
  const kvMode = !!doc && fieldsEditable(doc.format || '')

  const rows = useMemo(() => {
    if (!doc || !kvMode) return []
    return buildRows(draft, doc.fields)
  }, [doc, draft, kvMode])

  const dirty = useMemo(() => {
    if (!data?.documents) return false
    return data.documents.some((d) => (drafts[d.id] ?? d.content ?? '') !== (d.content || ''))
  }, [data, drafts])

  const filtered = useMemo(() => {
    const q = filter.trim().toLowerCase()
    if (!q) return rows
    return rows.filter(
      (r) =>
        r.key.toLowerCase().includes(q) ||
        (r.label || '').toLowerCase().includes(q) ||
        (r.help || '').toLowerCase().includes(q) ||
        r.value.toLowerCase().includes(q),
    )
  }, [rows, filter])

  const groups = useMemo(() => {
    const map = new Map<string, KvRow[]>()
    for (const r of filtered) {
      const g = r.group || 'config'
      if (!map.has(g)) map.set(g, [])
      map.get(g)!.push(r)
    }
    return [...map.entries()]
  }, [filtered])

  const diskRoles = useMemo(() => diskLayoutRoles(diskLayout), [diskLayout])

  const applyRows = (nextRows: KvRow[]) => {
    if (!doc) return
    const docId = doc.id
    const fallback = doc.content ?? ''
    setDrafts((prev) => {
      const base = prev[docId] ?? fallback
      return {
        ...prev,
        [docId]: serializeLooseKV(base, nextRows),
      }
    })
  }

  const setRowValue = (key: string, value: string) => {
    const next = rows.map((r) => (r.key.toLowerCase() === key.toLowerCase() ? { ...r, value } : r))
    applyRows(next)
  }

  const removeRow = (key: string) => {
    const row = rows.find((r) => r.key.toLowerCase() === key.toLowerCase())
    if (!row || row.protected) {
      if (row?.protected) {
        notifications.show({
          color: 'red',
          title: 'Key locked',
          message: `Cannot remove locked key: ${key}`,
        })
      }
      return
    }
    applyRows(rows.filter((r) => r.key.toLowerCase() !== key.toLowerCase()))
  }

  const addRow = () => {
    const k = newKey.trim()
    const v = newVal
    if (!k) {
      notifications.show({ color: 'yellow', message: 'Enter a key name' })
      return
    }
    if (!doc) {
      notifications.show({ color: 'red', message: 'No config document loaded' })
      return
    }
    if (rows.some((r) => r.key.toLowerCase() === k.toLowerCase())) {
      notifications.show({
        color: 'yellow',
        title: 'Key already exists',
        message: `Edit the existing row for “${k}” (including empty schema keys).`,
      })
      return
    }
    const locked = lockedAddKeyReason(k)
    if (locked) {
      notifications.show({
        color: 'red',
        title: 'Key locked',
        message: locked,
      })
      return
    }
    // Append directly to draft — avoid serializeLooseKV(rows) round-trip dropping the new key
    // when curated/schema rows and file content diverge.
    const docId = doc.id
    const fallback = doc.content ?? ''
    let appended = false
    setDrafts((prev) => {
      const base = prev[docId] ?? fallback
      if (parseLooseKV(base).some((p) => p.key.toLowerCase() === k.toLowerCase())) {
        return prev
      }
      let out = base
      if (out && !out.endsWith('\n')) out += '\n'
      out += `${k}=${v}\n`
      appended = true
      return { ...prev, [docId]: out }
    })
    if (!appended) {
      notifications.show({
        color: 'yellow',
        title: 'Key already exists',
        message: `Key “${k}” is already in the file — edit the row above.`,
      })
      return
    }
    // Filter can hide the new row after inputs clear — looks like Add did nothing.
    const q = filter.trim().toLowerCase()
    if (q && !k.toLowerCase().includes(q) && !String(v).toLowerCase().includes(q)) {
      setFilter('')
    }
    setNewKey('')
    setNewVal('')
  }

  const confirmSave = async () => {
    if (!data?.documents) return
    setSaving(true)
    try {
      const changed = data.documents
        .filter((d) => d.writable && (drafts[d.id] ?? '') !== (d.content || ''))
        .map((d) => ({ id: d.id, content: drafts[d.id] ?? '' }))
      if (changed.length === 0) {
        setConfirmOpen(false)
        return
      }
      const res = await api.nodeConfigSave(target, {
        confirm: true,
        restart: true,
        documents: changed,
      })
      notifications.show({
        color: 'teal',
        title: 'Config saved',
        message: res.message || 'Soft stop→start scheduled.',
      })
      setConfirmOpen(false)
      await load()
      onApplied?.()
      if (mode === 'modal') onClose?.()
    } catch (e) {
      notifications.show({
        color: 'red',
        title: 'Save failed',
        message: e instanceof Error ? e.message : String(e),
      })
    } finally {
      setSaving(false)
    }
  }

  if (mode === 'card' && !enabled) return null
  if (mode === 'modal' && !opened) return null

  const body = (
        <Stack gap="sm">
          {mode === 'card' ? (
            <Group justify="space-between" align="flex-start" wrap="wrap">
              <div>
                <Title order={4}>Node config</Title>
                <Text size="xs" c="dimmed">
                  Key → value · ports & datadir locked · save = soft stop→start ({network}/{env})
                </Text>
              </div>
              <Group gap="xs">
                <Button
                  size="xs"
                  variant="default"
                  leftSection={<IconRefresh size={14} />}
                  loading={loading}
                  onClick={() => void load()}
                >
                  Reload
                </Button>
                <Button
                  size="xs"
                  color="teal"
                  leftSection={<IconDeviceFloppy size={14} />}
                  disabled={!dirty || saving}
                  onClick={() => setConfirmOpen(true)}
                >
                  Save & restart
                </Button>
              </Group>
            </Group>
          ) : (
            <Group justify="space-between" align="center" wrap="wrap">
              <Text size="xs" c="dimmed">
                {network}/{env} · ports & datadir locked · save soft-stops & starts the node
              </Text>
              <Group gap="xs">
                <Button
                  size="xs"
                  variant="default"
                  leftSection={<IconRefresh size={14} />}
                  loading={loading}
                  onClick={() => void load()}
                >
                  Reload
                </Button>
                <Button
                  size="xs"
                  color="teal"
                  leftSection={<IconDeviceFloppy size={14} />}
                  disabled={!dirty || saving}
                  onClick={() => setConfirmOpen(true)}
                >
                  Save & restart
                </Button>
              </Group>
            </Group>
          )}

          {loading && !data ? (
            <Group gap="sm">
              <Loader size="sm" />
              <Text size="sm" c="dimmed">
                Loading config…
              </Text>
            </Group>
          ) : null}

          {error ? (
            <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Config unavailable">
              {error}
            </Alert>
          ) : null}

          {diskRoles.length > 0 ? (
            <Card withBorder padding="sm" radius="md">
              <Group justify="space-between" mb={6} wrap="wrap">
                <div>
                  <Text size="sm" fw={600}>
                    Disk layout
                  </Text>
                  <Text size="xs" c="dimmed">
                    Confirmed at Install · panel DB · used on re-provision
                    {diskLayout?.strategy ? ` · ${diskLayout.strategy}` : ''}
                  </Text>
                </div>
                <Badge size="sm" variant="light" color="gray" leftSection={<IconLock size={12} />}>
                  read-only
                </Badge>
              </Group>
              <Table striped highlightOnHover withTableBorder={false} verticalSpacing={4}>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Role</Table.Th>
                    <Table.Th>Mount</Table.Th>
                    <Table.Th>Dir</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {diskRoles.map((r) => (
                    <Table.Tr key={r.id}>
                      <Table.Td>
                        <Text size="sm">{r.label || r.id}</Text>
                      </Table.Td>
                      <Table.Td>
                        <Text size="xs" className="mono" c="dimmed">
                          {r.mount || '—'}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        <Group gap={4} wrap="nowrap">
                          <Text size="xs" className="mono" style={{ wordBreak: 'break-all' }}>
                            {r.dir || '—'}
                          </Text>
                          {r.dir ? <CopyValueButton value={r.dir} label={r.id} /> : null}
                        </Group>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Card>
          ) : null}

          {data?.documents && data.documents.length > 1 ? (
            <Select
              label="Document"
              data={data.documents.map((d) => ({
                value: d.id,
                label: d.title || d.id,
              }))}
              value={activeId}
              onChange={(v) => v && setActiveId(v)}
            />
          ) : null}

          {doc ? (
            <Stack gap="sm">
              <Group gap="xs">
                <Badge size="sm" variant="light">
                  {doc.format}
                </Badge>
                {doc.missing ? (
                  <Badge size="sm" color="orange" variant="light">
                    missing
                  </Badge>
                ) : null}
                {!doc.writable ? (
                  <Badge size="sm" color="gray" variant="light">
                    read-only
                  </Badge>
                ) : null}
                {kvMode ? (
                  <Badge size="sm" color="teal" variant="light">
                    {rows.length} keys
                  </Badge>
                ) : null}
                <Text size="xs" c="dimmed" className="mono" style={{ wordBreak: 'break-all' }}>
                  {doc.path}
                </Text>
              </Group>
              {doc.description ? (
                <Text size="sm" c="dimmed">
                  {doc.description}
                </Text>
              ) : null}

              {kvMode ? (
                <>
                  <TextInput
                    placeholder="Filter keys…"
                    size="xs"
                    value={filter}
                    onChange={(e) => setFilter(e.currentTarget.value)}
                  />

                  {groups.map(([group, list]) => (
                    <Box key={group}>
                      <Text
                        size="xs"
                        tt="uppercase"
                        c="dimmed"
                        fw={700}
                        mb={6}
                        style={{ letterSpacing: 0.4 }}
                      >
                        {group}
                      </Text>
                      <Table.ScrollContainer minWidth={520}>
                        <Table
                          striped
                          highlightOnHover
                          withTableBorder
                          verticalSpacing={6}
                          horizontalSpacing="sm"
                        >
                          <Table.Thead>
                            <Table.Tr>
                              <Table.Th style={{ width: '32%' }}>Key</Table.Th>
                              <Table.Th>Value</Table.Th>
                              <Table.Th style={{ width: 44 }} />
                            </Table.Tr>
                          </Table.Thead>
                          <Table.Tbody>
                            {list.map((r) => (
                              <KvTableRow
                                key={r.key}
                                row={r}
                                disabled={!doc.writable || !!r.protected}
                                onChange={(v) => setRowValue(r.key, v)}
                                onRemove={() => removeRow(r.key)}
                              />
                            ))}
                          </Table.Tbody>
                        </Table>
                      </Table.ScrollContainer>
                    </Box>
                  ))}

                  {doc.writable ? (
                    <Group align="flex-end" gap="xs" wrap="nowrap">
                      <TextInput
                        label="Add key"
                        placeholder="key"
                        size="xs"
                        className="mono"
                        style={{ flex: 1 }}
                        value={newKey}
                        onChange={(e) => setNewKey(e.currentTarget.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') addRow()
                        }}
                      />
                      <TextInput
                        label="Value"
                        placeholder="value"
                        size="xs"
                        className="mono"
                        style={{ flex: 1.4 }}
                        value={newVal}
                        onChange={(e) => setNewVal(e.currentTarget.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') addRow()
                        }}
                      />
                      <Button
                        size="xs"
                        variant="light"
                        leftSection={<IconPlus size={14} />}
                        onClick={addRow}
                        disabled={!newKey.trim()}
                      >
                        Add
                      </Button>
                    </Group>
                  ) : null}
                </>
              ) : (
                <Alert color="gray" variant="light">
                  This document is not key=value ({doc.format}). Edit raw below.
                </Alert>
              )}

              <Accordion variant="separated" radius="sm">
                <Accordion.Item value="raw">
                  <Accordion.Control>
                    <Text size="sm">Raw file {dirty ? '· modified' : ''}</Text>
                  </Accordion.Control>
                  <Accordion.Panel>
                    <Textarea
                      description="Advanced. Ports / datadir / locked keys still rejected on save."
                      minRows={10}
                      autosize
                      maxRows={28}
                      value={draft}
                      disabled={!doc.writable}
                      onChange={(e) =>
                        setDrafts((prev) => ({
                          ...prev,
                          [doc.id]: e.currentTarget.value,
                        }))
                      }
                      styles={{
                        input: {
                          fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                          fontSize: 12,
                        },
                      }}
                    />
                  </Accordion.Panel>
                </Accordion.Item>
              </Accordion>
            </Stack>
          ) : null}
        </Stack>
  )

  const confirmModal = (
      <Modal
        opened={confirmOpen}
        onClose={() => (!saving ? setConfirmOpen(false) : undefined)}
        title="Save config & soft-restart?"
        centered
        zIndex={400}
      >
        <Stack gap="md">
          <Text size="sm">
            Write changed files for{' '}
            <Text span fw={700}>
              {network}/{env}
            </Text>
            , then soft-stop and start via systemd. Public Go RPC sleeps (503) during the bounce.
          </Text>
          <Alert color="yellow" variant="light" icon={<IconAlertTriangle size={16} />}>
            Ports, datadir and agent auth keys stay locked. Chain data is not wiped.
          </Alert>
          <ChangedList documents={data?.documents} drafts={drafts} />
          <Group justify="flex-end">
            <Button variant="default" disabled={saving} onClick={() => setConfirmOpen(false)}>
              Cancel
            </Button>
            <Button
              color="teal"
              loading={saving}
              leftSection={<IconDeviceFloppy size={14} />}
              onClick={() => void confirmSave()}
            >
              Confirm save & restart
            </Button>
          </Group>
        </Stack>
      </Modal>
  )

  if (mode === 'modal') {
    return (
      <>
        <Modal
          opened={opened}
          onClose={() => {
            if (saving || confirmOpen) return
            onClose?.()
          }}
          title={
            <Group gap={6} wrap="nowrap" align="center">
              <Text fw={600} size="sm">
                Node config · {network}/{env}
              </Text>
              <Tooltip label="Official fullnode docs">
                <ActionIcon
                  component="a"
                  href={officialDocsUrl(network)}
                  target="_blank"
                  rel="noopener noreferrer"
                  variant="subtle"
                  color="gray"
                  size="sm"
                  aria-label="Official fullnode documentation"
                >
                  <IconBook2 size={16} />
                </ActionIcon>
              </Tooltip>
            </Group>
          }
          size="xl"
          centered
          styles={{ body: { maxHeight: 'min(80vh, 820px)', overflow: 'auto' } }}
        >
          {body}
        </Modal>
        {confirmModal}
      </>
    )
  }

  return (
    <>
      <Card withBorder padding="md" radius="md">
        {body}
      </Card>
      {confirmModal}
    </>
  )
}

function KvTableRow({
  row,
  disabled,
  onChange,
  onRemove,
}: {
  row: KvRow
  disabled?: boolean
  onChange: (v: string) => void
  onRemove: () => void
}) {
  const title = row.label && row.label !== row.key ? `${row.label}` : row.key
  return (
    <Table.Tr>
      <Table.Td>
        <Group gap={6} wrap="nowrap" align="flex-start">
          {row.protected ? (
            <Tooltip label="Locked — ports / datadir / agent wiring">
              <IconLock size={14} style={{ marginTop: 3, opacity: 0.7 }} />
            </Tooltip>
          ) : null}
          <div style={{ minWidth: 0 }}>
            <Text size="sm" fw={600} className="mono" style={{ wordBreak: 'break-all' }}>
              {row.key}
            </Text>
            {row.help || (row.label && row.label !== row.key) ? (
              <Text size="xs" c="dimmed" lineClamp={2}>
                {row.help || title}
              </Text>
            ) : null}
          </div>
        </Group>
      </Table.Td>
      <Table.Td>
        <ValueCell row={row} disabled={disabled} onChange={onChange} />
      </Table.Td>
      <Table.Td>
        {!row.protected && !disabled ? (
          <Tooltip label="Remove key">
            <ActionIcon size="sm" variant="subtle" color="gray" onClick={onRemove}>
              <IconTrash size={14} />
            </ActionIcon>
          </Tooltip>
        ) : null}
      </Table.Td>
    </Table.Tr>
  )
}

function ValueCell({
  row,
  disabled,
  onChange,
}: {
  row: KvRow
  disabled?: boolean
  onChange: (v: string) => void
}) {
  if (row.type === 'bool') {
    const on = row.value === '1' || row.value === 'true'
    return (
      <Switch
        size="sm"
        checked={on}
        disabled={disabled}
        onChange={(e) => onChange(e.currentTarget.checked ? '1' : '0')}
        label={on ? 'true' : 'false'}
      />
    )
  }
  if (row.type === 'enum' && row.options?.length) {
    return (
      <Select
        size="xs"
        data={row.options.map((o) => ({ value: o, label: o }))}
        value={row.value}
        disabled={disabled}
        onChange={(v) => v != null && onChange(v)}
        allowDeselect={false}
      />
    )
  }

  const secret = isSecretConfigKey(row.key)
  const copyable = isCopyableConfigKey(row.key)
  const input = secret ? (
    <PasswordInput
      size="xs"
      className="mono"
      value={row.value}
      disabled={disabled}
      onChange={(e) => onChange(e.currentTarget.value)}
      autoComplete="off"
      styles={monoInputStyles}
      style={{ flex: 1, minWidth: 0 }}
    />
  ) : (
    <TextInput
      size="xs"
      className="mono"
      value={row.value}
      disabled={disabled}
      onChange={(e) => onChange(e.currentTarget.value)}
      styles={monoInputStyles}
      style={{ flex: 1, minWidth: 0 }}
    />
  )

  if (!copyable) return input

  return (
    <Group gap={4} wrap="nowrap" align="center">
      {input}
      <CopyValueButton value={row.value} label={row.key} />
    </Group>
  )
}

function ChangedList({
  documents,
  drafts,
}: {
  documents?: NodeConfigDocument[]
  drafts: Record<string, string>
}) {
  const changed = (documents || []).filter(
    (d) => d.writable && (drafts[d.id] ?? '') !== (d.content || ''),
  )
  if (!changed.length) return null
  return (
    <Stack gap={4}>
      <Text size="xs" c="dimmed">
        Files to write:
      </Text>
      {changed.map((d) => (
        <Text key={d.id} size="xs" className="mono">
          {d.path}
        </Text>
      ))}
    </Stack>
  )
}
