import { ActionIcon, Alert, Button, Card, Group, Stack, Table, Text, TextInput, Tooltip } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconArrowBackUp, IconPlus, IconTrash } from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'

/** Flags the catalog decides (ports, paths, network, sync mode) — never editable; the host rejects them too. */
const LOCKED = new Set([
  'datadir', 'port', 'http', 'http.port', 'http.addr', 'ws', 'ws.port', 'ws.addr',
  'authrpc.addr', 'authrpc.port', 'authrpc.jwtsecret', 'authrpc.vhosts',
  'syncmode', 'gcmode', 'state.scheme', 'ipcdisable', 'ipcpath', 'history.chain',
  'mainnet', 'sepolia', 'holesky', 'hoodi', 'networkid', 'config', 'datadir.ancient',
])

const KEY = /^[a-z][a-z0-9.-]{0,63}$/
const VALUE = /^[A-Za-z0-9_.,:/*@+=-]{0,256}$/

/** What the unit sets without overrides. Mirrors EthereumGethFlags.defaults on the host (the unit is authoritative). */
function gethDefaults(env: string): Record<string, string> {
  const e = (env || '').toLowerCase()
  return {
    'http.api': 'eth,net,web3,txpool',
    'http.vhosts': '*',
    cache: e === 'sepolia' || e === 'hoodi' ? '2048' : '4096',
    maxpeers: '100',
    'rpc.batch-request-limit': '2000',
  }
}

/** Recommended starting point for a busy RPC (L2 nodes, indexers, public endpoint). Not benchmarked: review it. */
function highLoadPreset(defaults: Record<string, string>): Record<string, string> {
  return {
    cache: String(Number(defaults.cache) * 2),
    'cache.blocklogs': '128',
    'rpc.batch-response-max-size': '100000000',
  }
}

type Row = { key: string; value: string }

function parseOverrides(text: string): Record<string, string> {
  const out: Record<string, string> = {}
  for (const raw of (text || '').split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const body = line.replace(/^--/, '')
    const m = body.search(/[= ]/)
    const key = (m < 0 ? body : body.slice(0, m)).trim()
    const value = m < 0 ? '' : body.slice(m + 1).trim()
    if (key) out[key] = value
  }
  return out
}

/**
 * Geth flags as key/value rows of an installed node (Node config). Rows equal to the default are not stored;
 * changed and added ones go to install_options.geth_flags and are rendered into the unit on the next restart.
 */
export function GethFlagsEditor({
  nodeId,
  network,
  env,
  installOptions,
  onApplied,
}: {
  nodeId?: string
  network: string
  env: string
  installOptions: Record<string, string> | null
  onApplied?: () => void
}) {
  const defaults = useMemo(() => gethDefaults(env), [env])
  const saved = installOptions || {}
  const savedText = saved.geth_flags || ''
  const initial = useMemo(() => ({ ...defaults, ...parseOverrides(savedText) }), [defaults, savedText])
  const [rows, setRows] = useState<Row[]>([])
  const [newKey, setNewKey] = useState('')
  const [newValue, setNewValue] = useState('')
  const [busy, setBusy] = useState<'save' | 'restart' | null>(null)

  useEffect(() => {
    setRows(Object.entries(initial).map(([key, value]) => ({ key, value })))
  }, [initial])

  if (!nodeId || (network || '').toLowerCase() !== 'ethereum') return null

  const problems = rows.flatMap((r) => {
    const out: string[] = []
    if (!KEY.test(r.key)) out.push(`${r.key}: key must be lowercase letters, digits, "." or "-"`)
    if (LOCKED.has(r.key)) out.push(`${r.key}: set by the catalog, cannot be changed`)
    if (!VALUE.test(r.value)) out.push(`${r.key}: value may only contain letters, digits and _ . , : / * @ + = -`)
    return out
  })
  const overridesText = rows
    .filter((r) => r.key && defaults[r.key] !== r.value)
    .map((r) => (r.value ? `${r.key}=${r.value}` : r.key))
    .join('\n')
  const dirty = overridesText !== savedText.trim()

  function setValue(key: string, value: string) {
    setRows((rs) => rs.map((r) => (r.key === key ? { ...r, value } : r)))
  }

  function addRow() {
    const key = newKey.trim().replace(/^--/, '')
    if (!key) return
    setRows((rs) => (rs.some((r) => r.key === key) ? rs.map((r) => (r.key === key ? { ...r, value: newValue.trim() } : r)) : [...rs, { key, value: newValue.trim() }]))
    setNewKey('')
    setNewValue('')
  }

  function applyPreset() {
    const preset = highLoadPreset(defaults)
    setRows((rs) => {
      const next = rs.map((r) => (r.key in preset ? { ...r, value: preset[r.key] } : r))
      for (const [key, value] of Object.entries(preset)) if (!next.some((r) => r.key === key)) next.push({ key, value })
      return next
    })
  }

  async function apply(restart: boolean) {
    if (problems.length) return
    setBusy(restart ? 'restart' : 'save')
    try {
      // install_options is one document: send it whole with only geth_flags changed.
      const res = await api.workloadsSaveInstallOptions(nodeId!, {
        install_options: { ...saved, geth_flags: overridesText },
      })
      if (res.ok === false) throw new Error(res.message || res.error || 'save failed')
      if (restart) {
        const r = await api.workloadsNodeProcessRestart(nodeId!)
        if (r.ok === false) throw new Error(r.message || r.error || 'restart failed')
      }
      notifications.show({
        color: 'teal',
        message: restart ? 'Saved, geth restarted with the new flags' : 'Saved — restart the node to apply',
      })
      onApplied?.()
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setBusy(null)
    }
  }

  return (
    <Card withBorder padding="sm" radius="md">
      <Stack gap="xs">
        <Group justify="space-between" wrap="wrap">
          <div>
            <Text size="sm" fw={600}>
              Geth flags
            </Text>
            <Text size="xs" c="dimmed">
              key = value · editable any time · applied on restart · ports, paths and network are locked
            </Text>
          </div>
          <Tooltip label="--cache ×2, --cache.blocklogs 128, --rpc.batch-response-max-size 100000000. Needs the RAM; not benchmarked.">
            <Button size="compact-xs" variant="light" onClick={applyPreset}>
              Prod recommendations
            </Button>
          </Tooltip>
        </Group>
        <Table verticalSpacing={4} withTableBorder={false}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Key (--flag)</Table.Th>
              <Table.Th>Value</Table.Th>
              <Table.Th w={70} />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {rows.map((r) => {
              const isDefaultKey = r.key in defaults
              const changed = defaults[r.key] !== r.value
              return (
                <Table.Tr key={r.key}>
                  <Table.Td>
                    <Text size="sm" className="mono" c={LOCKED.has(r.key) ? 'red' : undefined}>
                      --{r.key}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <TextInput
                      size="xs"
                      value={r.value}
                      placeholder="(flag without value)"
                      styles={changed ? { input: { borderColor: 'var(--mantine-color-yellow-6)' } } : undefined}
                      onChange={(e) => setValue(r.key, e.currentTarget.value)}
                    />
                  </Table.Td>
                  <Table.Td>
                    <Group gap={2} wrap="nowrap">
                      {isDefaultKey && changed ? (
                        <Tooltip label={`Back to default (${defaults[r.key]})`}>
                          <ActionIcon size="sm" variant="subtle" color="gray" onClick={() => setValue(r.key, defaults[r.key])}>
                            <IconArrowBackUp size={14} />
                          </ActionIcon>
                        </Tooltip>
                      ) : null}
                      {!isDefaultKey ? (
                        <Tooltip label="Remove flag">
                          <ActionIcon size="sm" variant="subtle" color="red" onClick={() => setRows((rs) => rs.filter((x) => x.key !== r.key))}>
                            <IconTrash size={14} />
                          </ActionIcon>
                        </Tooltip>
                      ) : null}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              )
            })}
            <Table.Tr>
              <Table.Td>
                <TextInput size="xs" placeholder="new flag, e.g. rpc.gascap" value={newKey} onChange={(e) => setNewKey(e.currentTarget.value)} />
              </Table.Td>
              <Table.Td>
                <TextInput size="xs" placeholder="value" value={newValue} onChange={(e) => setNewValue(e.currentTarget.value)} onKeyDown={(e) => e.key === 'Enter' && addRow()} />
              </Table.Td>
              <Table.Td>
                <ActionIcon size="sm" variant="light" aria-label="Add flag" disabled={!newKey.trim()} onClick={addRow}>
                  <IconPlus size={14} />
                </ActionIcon>
              </Table.Td>
            </Table.Tr>
          </Table.Tbody>
        </Table>
        {problems.length ? (
          <Alert color="red" variant="light" p="xs">
            {problems.map((p) => (
              <div key={p}>{p}</div>
            ))}
          </Alert>
        ) : dirty ? (
          <Alert color="yellow" variant="light" p="xs">
            Not applied yet. A restart stops geth first (can take a few minutes).
          </Alert>
        ) : null}
        <Group justify="flex-end" gap="xs">
          <Button size="xs" variant="light" disabled={!dirty || problems.length > 0 || busy != null} loading={busy === 'save'} onClick={() => void apply(false)}>
            Save
          </Button>
          <Button size="xs" disabled={problems.length > 0 || busy != null} loading={busy === 'restart'} onClick={() => void apply(true)}>
            {dirty ? 'Save & restart' : 'Restart to apply'}
          </Button>
        </Group>
      </Stack>
    </Card>
  )
}
