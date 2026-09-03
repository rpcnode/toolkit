import { useCallback, useEffect, useState } from 'react'
import { ActionIcon, Badge, Code, Group, Loader, Stack, Text, Tooltip } from '@mantine/core'
import { IconCopy, IconRefresh } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { api, getJSONResult, type ApiCallResult, type NodePortItem, type NodePortsResponse } from '../api'
import { copyText } from '../lib/copyText'
import { blockData, blockProps } from '../lib/blockId'
import { ApiFetchIssue } from './ApiFetchIssue'

type Props = {
  nodeId: string
  serverId: string
  network: string
  env: string
  /** Probe host bind status automatically while the chain unit is running. */
  liveWhenRunning?: boolean
}

/** Host from the shared endpoint + catalog port → full service URL. */
function portStatusUrl(baseEndpoint: string | null, port: number): string | null {
  const raw = String(baseEndpoint || '').trim()
  if (!raw) return null
  try {
    const u = new URL(raw)
    if (!u.hostname) return null
    return `${u.protocol}//${u.hostname}:${port}`
  } catch {
    return null
  }
}

function copyPortUrl(url: string, message: string) {
  void copyText(url).then(() => {
    notifications.show({ color: 'teal', message, autoClose: 2000 })
  })
}

function applyPortPayload(data: NodePortsResponse) {
  return {
    items: data.items || [],
    endpoint: data.endpoint || null,
    message: data.ok === false ? data.message || data.error || 'Could not check ports' : null,
  }
}

/** Fixed catalog ports; live free/busy from the host agent when [liveWhenRunning]. */
export function NodePortsPanel({ nodeId, serverId, network, env, liveWhenRunning = false }: Props) {
  const [items, setItems] = useState<NodePortItem[]>([])
  const [endpoint, setEndpoint] = useState<string | null>(null)
  const [message, setMessage] = useState<string | null>(null)
  const [fetchIssue, setFetchIssue] = useState<ApiCallResult<NodePortsResponse> | null>(null)
  const [loading, setLoading] = useState(false)
  const [checking, setChecking] = useState(false)
  const [loaded, setLoaded] = useState(false)

  const canCheckLive = !!(serverId && network && env)

  const loadCatalog = useCallback(async () => {
    if (!nodeId) return
    setLoading(true)
    const path = `/api/nodes/${encodeURIComponent(nodeId)}/ports`
    const res = await getJSONResult<NodePortsResponse>(path)
    setFetchIssue(res.ok ? null : res)
    if (res.ok) {
      const next = applyPortPayload(res.data)
      setItems(next.items)
      setEndpoint(next.endpoint)
      setMessage(null)
    } else {
      setItems([])
      setEndpoint(null)
      setMessage(null)
    }
    setLoading(false)
    setLoaded(true)
  }, [nodeId])

  const checkLive = useCallback(async () => {
    if (!canCheckLive) return
    setChecking(true)
    const res = await api.checkHostPortsResult({ server_id: serverId, network, env })
    setFetchIssue(res.ok ? null : res)
    if (res.ok) {
      const next = applyPortPayload(res.data)
      setItems(next.items)
      setEndpoint(next.endpoint)
      setMessage(next.message)
    } else {
      setMessage(null)
    }
    setLoaded(true)
    setChecking(false)
    setLoading(false)
  }, [canCheckLive, serverId, network, env])

  useEffect(() => {
    setLoaded(false)
    setMessage(null)
    if (liveWhenRunning && canCheckLive) {
      setLoading(true)
      void checkLive()
      return
    }
    void loadCatalog()
  }, [liveWhenRunning, canCheckLive, loadCatalog, checkLive])

  useEffect(() => {
    if (!liveWhenRunning || !canCheckLive) return
    const t = window.setInterval(() => void checkLive(), 30_000)
    return () => window.clearInterval(t)
  }, [liveWhenRunning, canCheckLive, checkLive])

  if ((!loaded && loading) || (liveWhenRunning && checking && !items.length)) {
    return (
      <Group gap={6}>
        <Loader size={12} />
        <Text size="xs" c="dimmed">
          {liveWhenRunning ? 'Checking ports on host…' : 'Loading ports…'}
        </Text>
      </Group>
    )
  }

  if (fetchIssue && !fetchIssue.ok && !items.length) {
    return (
      <Stack gap={6}>
        <ApiFetchIssue title="Ports request failed" result={fetchIssue} />
        <Group justify="flex-end">
          <Tooltip label="Retry">
            <ActionIcon size="xs" variant="subtle" color="gray" aria-label="Retry" loading={checking || loading} onClick={() => void (liveWhenRunning ? checkLive() : loadCatalog())}>
              <IconRefresh size={12} />
            </ActionIcon>
          </Tooltip>
        </Group>
      </Stack>
    )
  }

  if (!items.length) {
    return (
      <Text size="xs" c="dimmed">
        {message || 'No fixed ports for this network/env'}
      </Text>
    )
  }

  return (
    <Stack gap={6} {...blockProps('node.detail.ports-panel')}>
      {fetchIssue && !fetchIssue.ok ? (
        <ApiFetchIssue title="Ports request failed" result={fetchIssue} />
      ) : null}
      {message ? (
        <Text size="xs" c="orange.4">
          {message}
        </Text>
      ) : null}
      {items.map((it) => {
        const portUrl = portStatusUrl(endpoint, it.port)
        return (
        <Group key={`${it.role}-${it.port}`} gap={6} wrap="nowrap" justify="space-between" {...blockData(`node.detail.ports-panel.row.${it.role}`)}>
          <Text size="xs" c="gray.4" title={it.role} style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis' }}>
            {it.label || it.role}
          </Text>
          <Group gap={6} wrap="nowrap" style={{ flexShrink: 0 }}>
            <Code className="mono" style={{ fontSize: 11 }}>
              {it.port}
            </Code>
            {it.free == null ? (
              <Badge size="xs" color="gray" variant="light">
                unknown
              </Badge>
            ) : it.free ? (
              <Tooltip label="Port is free — nothing listening">
                <Badge size="xs" color="red" variant="light">
                  free
                </Badge>
              </Tooltip>
            ) : (
              <Tooltip label={it.holder ? `Listening · ${it.holder}` : 'Port in use (listening)'}>
                <Badge size="xs" color="teal" variant="light">
                  busy
                </Badge>
              </Tooltip>
            )}
            {portUrl ? (
              <Tooltip label={`Copy ${portUrl}`}>
                <ActionIcon
                  size="xs"
                  variant="light"
                  color="gray"
                  aria-label={`Copy ${portUrl}`}
                  onClick={() => copyPortUrl(portUrl, 'Port URL copied')}
                >
                  <IconCopy size={12} />
                </ActionIcon>
              </Tooltip>
            ) : null}
          </Group>
        </Group>
        )
      })}
      <Group justify="flex-end">
        <Tooltip label="Check ports on host">
          <ActionIcon
            size="xs"
            variant="subtle"
            color="gray"
            aria-label="Check ports"
            loading={checking}
            onClick={() => void checkLive()}
          >
            <IconRefresh size={12} />
          </ActionIcon>
        </Tooltip>
      </Group>
    </Stack>
  )
}
