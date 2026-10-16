import { ActionIcon, Alert, Badge, Button, Card, Code, Group, Select, Stack, Text, TextInput, Title, Tooltip } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconPencil, IconTrash, IconX } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { api, type ProxyItem } from '../api'
import { blockProps } from '../lib/blockId'

/**
 * Settings → Proxies: the global list nodes pick from (install wizard or node page). Only `http://` proxies:
 * every downloader on the host (aria2, curl, the chain snapshot tools, Java) understands them.
 */
export function ProxiesCard() {
  const [items, setItems] = useState<ProxyItem[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editId, setEditId] = useState<string | null>(null)
  const [name, setName] = useState('')
  const [url, setUrl] = useState('')
  const [servers, setServers] = useState<{ value: string; label: string }[]>([])
  // '' = check from the panel; otherwise a server id (that host's agent makes the connect)
  const [checkFrom, setCheckFrom] = useState('')
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null)
  const [unreachable, setUnreachable] = useState(false)

  async function load() {
    setLoading(true)
    try {
      const res = await api.proxies()
      setItems(res.items || [])
      setError(null)
    } catch (e) {
      setError(String((e as Error).message || e))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    api
      .registryList()
      .then((res) => {
        const list = (res.items || []).map((s) => ({ value: s.id, label: s.name || s.id }))
        setServers(list)
        // The node's traffic leaves from a server, so check from there by default.
        if (list.length > 0) setCheckFrom((cur) => cur || list[0].value)
      })
      .catch(() => setServers([]))
  }, [])

  function resetForm() {
    setEditId(null)
    setName('')
    setUrl('')
    setTestResult(null)
    setUnreachable(false)
  }

  async function test() {
    setTesting(true)
    setTestResult(null)
    try {
      const res = await api.testProxy({ id: editId || undefined, url: url.trim(), server_id: checkFrom || undefined })
      setTestResult({
        ok: res.ok,
        message: res.ok ? `Reachable · ${res.latency_ms ?? 0} ms` : res.message || 'Not reachable',
      })
    } catch (e) {
      setTestResult({ ok: false, message: String((e as Error).message || e) })
    } finally {
      setTesting(false)
    }
  }

  function startEdit(p: ProxyItem) {
    setEditId(p.id)
    setName(p.name)
    setUrl(p.url)
    setError(null)
    setTestResult(null)
    setUnreachable(false)
  }

  async function save(skipCheck = false) {
    setBusy(true)
    setError(null)
    setUnreachable(false)
    try {
      const body = {
        name: name.trim(),
        url: url.trim(),
        ...(checkFrom ? { server_id: checkFrom } : {}),
        ...(skipCheck ? { skip_check: true } : {}),
      }
      if (editId) await api.updateProxy(editId, body)
      else await api.createProxy(body)
      notifications.show({ color: 'teal', message: editId ? 'Proxy updated' : 'Proxy added' })
      resetForm()
      await load()
    } catch (e) {
      const msg = String((e as Error).message || e)
      setError(msg)
      setUnreachable(msg.startsWith('Proxy is not reachable'))
    } finally {
      setBusy(false)
    }
  }

  async function remove(p: ProxyItem) {
    setBusy(true)
    setError(null)
    try {
      const res = await api.deleteProxy(p.id)
      setItems(res.items || [])
      if (editId === p.id) resetForm()
      notifications.show({ color: 'teal', message: `${p.name} removed` })
    } catch (e) {
      setError(String((e as Error).message || e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card {...blockProps('settings.proxies')}>
      <Stack gap="sm">
        <div>
          <Title order={4}>Proxies</Title>
          <Text size="sm" c="dimmed">
            Outbound HTTP proxies a node can use (pick one in the install wizard or on the node page). A proxy is
            checked with a live HTTPS connection (from the panel or from a server's agent) before it is saved. Applied on
            the host for snapshot downloads and for the node's own systemd units on the next Start / Restart. Local
            traffic (<Code>localhost</Code>, <Code>127.0.0.1</Code>) stays direct.
          </Text>
        </div>
        {error ? (
          <Alert color="red" title="Proxies">
            {error}
            {unreachable ? (
              <Group mt="xs">
                <Button size="xs" variant="default" disabled={busy} onClick={() => void save(true)}>
                  Save anyway
                </Button>
              </Group>
            ) : null}
          </Alert>
        ) : null}
        {testResult ? (
          <Alert color={testResult.ok ? 'teal' : 'orange'} title={testResult.ok ? 'Proxy works' : 'Proxy check failed'}>
            {testResult.message}
          </Alert>
        ) : null}
        {!loading && items.length === 0 ? (
          <Text size="sm" c="dimmed">
            No proxies yet — every node connects directly.
          </Text>
        ) : null}
        {items.map((p) => (
          <Group key={p.id} justify="space-between" wrap="nowrap" gap="xs">
            <Stack gap={2} style={{ minWidth: 0 }}>
              <Group gap="xs" wrap="wrap">
                <Text fw={600} size="sm">
                  {p.name}
                </Text>
                {(p.nodes || []).length > 0 ? (
                  <Tooltip label={(p.nodes || []).join(', ')} withArrow>
                    <Badge size="xs" variant="light" color="teal">
                      {(p.nodes || []).length} node{(p.nodes || []).length === 1 ? '' : 's'}
                    </Badge>
                  </Tooltip>
                ) : (
                  <Badge size="xs" variant="light" color="gray">
                    unused
                  </Badge>
                )}
              </Group>
              <Code style={{ overflowWrap: 'anywhere' }}>{p.url}</Code>
            </Stack>
            <Group gap={4} wrap="nowrap">
              <ActionIcon variant="subtle" aria-label="Edit proxy" disabled={busy} onClick={() => startEdit(p)}>
                <IconPencil size={16} />
              </ActionIcon>
              <Tooltip
                label={(p.nodes || []).length > 0 ? 'Used by nodes — switch them first' : 'Remove'}
                withArrow
              >
                <ActionIcon
                  variant="subtle"
                  color="red"
                  aria-label="Remove proxy"
                  disabled={busy || (p.nodes || []).length > 0}
                  onClick={() => void remove(p)}
                >
                  <IconTrash size={16} />
                </ActionIcon>
              </Tooltip>
            </Group>
          </Group>
        ))}
        <Group align="flex-end" wrap="wrap" gap="sm">
          <TextInput
            label="Name"
            placeholder="EU proxy"
            value={name}
            onChange={(e) => setName(e.currentTarget.value)}
            disabled={loading || busy}
            style={{ flex: '1 1 160px' }}
          />
          <TextInput
            label="URL"
            placeholder="http://user:pass@host:3128"
            description={editId ? 'Leave *** to keep the saved password' : undefined}
            value={url}
            onChange={(e) => {
              setUrl(e.currentTarget.value.trim())
              setTestResult(null)
            }}
            disabled={loading || busy}
            style={{ flex: '2 1 260px' }}
          />
          <Select
            label="Check from"
            description="Where the connect test runs"
            data={[{ value: '', label: 'Panel' }, ...servers]}
            value={checkFrom}
            onChange={(v) => {
              setCheckFrom(v || '')
              setTestResult(null)
            }}
            allowDeselect={false}
            disabled={loading || busy || testing}
            style={{ flex: '1 1 160px' }}
          />
          <Group gap="xs">
            <Button variant="default" loading={testing} disabled={loading || busy || !url.trim()} onClick={() => void test()}>
              Test
            </Button>
            <Button color="teal" loading={busy} disabled={loading || testing || !url.trim()} onClick={() => void save()}>
              {editId ? 'Save proxy' : 'Add proxy'}
            </Button>
            {editId ? (
              <ActionIcon variant="default" size="lg" aria-label="Cancel edit" onClick={resetForm}>
                <IconX size={16} />
              </ActionIcon>
            ) : null}
          </Group>
        </Group>
      </Stack>
    </Card>
  )
}
