import { ActionIcon, Alert, Button, Card, Group, Stack, Table, Text, TextInput, Tooltip } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconPlus, IconTrash } from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { api } from '../api'
import { prodPresetFor } from '../lib/prodPresets'

const KEY = /^[a-z][a-z0-9._-]{0,63}$/
const VALUE = /^[A-Za-z0-9_.,:/*@+=-]{0,256}$/
/** Ports, paths, keys and network selection stay with the catalog (same rule as the host). */
const LOCKED_TOKENS = new Set([
  'port', 'datadir', 'dir', 'path', 'jwt', 'jwtsecret', 'secret', 'keypair', 'identity',
  'addr', 'address', 'chain', 'network', 'networkid', 'genesis', 'ledger', 'config', 'conf',
])
const isLocked = (key: string) => key.split(/[._-]/).some((t) => LOCKED_TOKENS.has(t))

type Row = { key: string; value: string }

function parse(text: string): Row[] {
  const rows: Row[] = []
  for (const raw of (text || '').split('\n')) {
    const line = raw.trim()
    if (!line || line.startsWith('#')) continue
    const body = line.replace(/^--/, '')
    const cut = body.search(/[= ]/)
    const key = (cut < 0 ? body : body.slice(0, cut)).trim()
    const value = cut < 0 ? '' : body.slice(cut + 1).trim()
    if (key) rows.push({ key, value })
  }
  return rows
}

/**
 * Flag overrides of a unit-based node, as key/value rows. A flag the unit already sets gets the new value, an unknown
 * one is appended; rendered into the unit on the next restart. Used for every flag client except the geth editor.
 */
export function ExtraFlagsEditor({
  nodeId,
  network,
  installOptions,
  onApplied,
}: {
  nodeId?: string
  network: string
  installOptions: Record<string, string> | null
  onApplied?: () => void
}) {
  const saved = installOptions || {}
  const savedText = (saved.extra_flags || '').trim()
  const preset = prodPresetFor(network)
  const [rows, setRows] = useState<Row[]>([])
  const [newKey, setNewKey] = useState('')
  const [newValue, setNewValue] = useState('')
  const [busy, setBusy] = useState<'save' | 'restart' | null>(null)

  useEffect(() => {
    setRows(parse(savedText))
  }, [savedText])

  const text = useMemo(
    () => rows.filter((r) => r.key).map((r) => (r.value ? `${r.key}=${r.value}` : r.key)).join('\n'),
    [rows],
  )
  const dirty = text !== savedText
  const problems = rows.flatMap((r) => {
    const out: string[] = []
    if (!KEY.test(r.key)) out.push(`${r.key}: key must be lowercase letters, digits, "." "-" "_"`)
    else if (isLocked(r.key)) out.push(`${r.key}: set by the catalog (port / path / key / network), cannot be changed`)
    if (!VALUE.test(r.value)) out.push(`${r.key}: value may only contain letters, digits and _ . , : / * @ + = -`)
    return out
  })

  if (!nodeId || !preset?.flags) return null
  const presetFlags = preset.flags
  const pfx = preset.prefix ?? '--'

  function set(key: string, value: string) {
    setRows((rs) => (rs.some((r) => r.key === key) ? rs.map((r) => (r.key === key ? { ...r, value } : r)) : [...rs, { key, value }]))
  }

  async function apply(restart: boolean) {
    if (problems.length) return
    setBusy(restart ? 'restart' : 'save')
    try {
      // install_options is one document: send it whole with only extra_flags changed.
      const res = await api.workloadsSaveInstallOptions(nodeId!, { install_options: { ...saved, extra_flags: text } })
      if (res.ok === false) throw new Error(res.message || res.error || 'save failed')
      if (restart) {
        const r = await api.workloadsNodeProcessRestart(nodeId!)
        if (r.ok === false) throw new Error(r.message || r.error || 'restart failed')
      }
      notifications.show({ color: 'teal', message: restart ? 'Saved, node restarted with the new flags' : 'Saved — restart the node to apply' })
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
              Flag overrides
            </Text>
            <Text size="xs" c="dimmed">
              key = value · replaces the unit's flag or adds a new one · applied on restart · ports, paths and keys are locked
            </Text>
          </div>
          <Tooltip label={(preset.notes || []).join(' · ') || 'Checked against the client flag reference'} multiline w={320}>
            <Button size="compact-xs" variant="light" onClick={() => Object.entries(presetFlags).forEach(([k, v]) => set(k, v))}>
              Prod recommendations
            </Button>
          </Tooltip>
        </Group>
        <Table verticalSpacing={4} withTableBorder={false}>
          <Table.Tbody>
            {rows.length === 0 ? (
              <Table.Tr>
                <Table.Td>
                  <Text size="xs" c="dimmed">
                    No overrides — the unit runs with the toolkit defaults.
                  </Text>
                </Table.Td>
              </Table.Tr>
            ) : null}
            {rows.map((r) => (
              <Table.Tr key={r.key}>
                <Table.Td>
                  <Text size="sm" className="mono">
                    {pfx}
                    {r.key}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <TextInput size="xs" value={r.value} placeholder="(flag without value)" onChange={(e) => set(r.key, e.currentTarget.value)} />
                </Table.Td>
                <Table.Td w={40}>
                  <ActionIcon size="sm" variant="subtle" color="red" aria-label="Remove flag" onClick={() => setRows((rs) => rs.filter((x) => x.key !== r.key))}>
                    <IconTrash size={14} />
                  </ActionIcon>
                </Table.Td>
              </Table.Tr>
            ))}
            <Table.Tr>
              <Table.Td>
                <TextInput size="xs" placeholder="new flag, e.g. rpc.gascap" value={newKey} onChange={(e) => setNewKey(e.currentTarget.value)} />
              </Table.Td>
              <Table.Td>
                <TextInput
                  size="xs"
                  placeholder="value"
                  value={newValue}
                  onChange={(e) => setNewValue(e.currentTarget.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' && newKey.trim()) {
                      set(newKey.trim().replace(/^--/, ''), newValue.trim())
                      setNewKey('')
                      setNewValue('')
                    }
                  }}
                />
              </Table.Td>
              <Table.Td w={40}>
                <ActionIcon
                  size="sm"
                  variant="light"
                  aria-label="Add flag"
                  disabled={!newKey.trim()}
                  onClick={() => {
                    set(newKey.trim().replace(/^--/, ''), newValue.trim())
                    setNewKey('')
                    setNewValue('')
                  }}
                >
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
            Not applied yet. A restart stops the node first.
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
