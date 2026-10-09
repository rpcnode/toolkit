import { Alert, Button, Checkbox, Group, Loader, Modal, Stack, Text } from '@mantine/core'
import { IconSearch } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useCallback, useEffect, useState } from 'react'
import { api, type DiscoveredNode } from '../api'
import { blockProps } from '../lib/blockId'

function nodeKey(n: DiscoveredNode): string {
  return `${n.network}/${n.env}`
}

type PanelProps = {
  serverId: string
  /** Called after at least one node was added (so callers can refresh their lists). */
  onImported?: () => void
  /** Called when the operator is done here — Skip, Close, or after a clean import. */
  onDone: () => void
  doneLabel?: string
}

/**
 * Scans a server's host for network/env pairs already provisioned there
 * (tip `GET /api/v1/nodes`) that this panel does not yet track as a node,
 * and lets the operator pick which ones to register — instead of re-typing
 * every network/env by hand for a host that already runs fullnodes.
 */
export function DiscoverNodesPanel({ serverId, onImported, onDone, doneLabel }: PanelProps) {
  const [loading, setLoading] = useState(false)
  const [items, setItems] = useState<DiscoveredNode[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [importing, setImporting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const scan = useCallback(async () => {
    if (!serverId) return
    setLoading(true)
    setError(null)
    try {
      const res = await api.discoverServerNodes(serverId)
      const found = res.items || []
      setItems(found)
      setSelected(new Set(found.map(nodeKey)))
    } catch (e) {
      setError(String((e as Error).message || e))
    } finally {
      setLoading(false)
    }
  }, [serverId])

  useEffect(() => {
    setItems([])
    setSelected(new Set())
    setError(null)
    void scan()
  }, [scan])

  function toggle(n: DiscoveredNode) {
    setSelected((prev) => {
      const next = new Set(prev)
      const k = nodeKey(n)
      if (next.has(k)) next.delete(k)
      else next.add(k)
      return next
    })
  }

  async function importSelected() {
    const toAdd = items.filter((n) => selected.has(nodeKey(n)))
    if (toAdd.length === 0) {
      onDone()
      return
    }
    setImporting(true)
    let ok = 0
    const failed: string[] = []
    for (const n of toAdd) {
      try {
        const res = await api.workloadsUpsert({ server_id: serverId, network: n.network, env: n.env })
        if (res.ok === false) throw new Error(res.message || res.error || 'failed')
        ok++
      } catch (e) {
        failed.push(`${n.network}/${n.env}: ${String((e as Error).message || e)}`)
      }
    }
    setImporting(false)
    if (ok > 0) {
      notifications.show({ color: 'teal', message: `Added ${ok} node${ok === 1 ? '' : 's'} from this host` })
      onImported?.()
    }
    if (failed.length > 0) {
      notifications.show({ color: 'red', message: failed.join('; ') })
      return // leave the modal open so the operator can retry the failed ones
    }
    onDone()
  }

  return (
    <Stack gap="md" {...blockProps('modal.discover-nodes.panel')}>
      {loading ? (
        <Group justify="center" py="md" gap="xs">
          <Loader size="sm" />
          <Text size="sm" c="dimmed">
            Scanning host for existing nodes…
          </Text>
        </Group>
      ) : error ? (
        <Alert color="red" title="Scan failed">
          {error}
        </Alert>
      ) : items.length === 0 ? (
        <Alert color="gray" variant="light">
          No unregistered nodes found on this host.
        </Alert>
      ) : (
        <Stack gap={6}>
          <Text size="sm" c="dimmed">
            Found {items.length} node{items.length === 1 ? '' : 's'} already provisioned on this host.
            Pick which to add to the panel.
          </Text>
          {items.map((n) => (
            <Checkbox
              key={nodeKey(n)}
              checked={selected.has(nodeKey(n))}
              onChange={() => toggle(n)}
              label={
                <Group gap={6} wrap="wrap">
                  <Text fw={600} span>
                    {n.label || n.network}
                  </Text>
                  <Text size="xs" c="dimmed" span>
                    {n.env}
                    {n.host_status ? ` · ${n.host_status}` : ''}
                    {n.public_port ? ` · :${n.public_port}` : ''}
                  </Text>
                </Group>
              }
            />
          ))}
        </Stack>
      )}
      <Group justify="space-between">
        <Button variant="default" disabled={importing} onClick={onDone}>
          {items.length > 0 ? 'Skip' : doneLabel || 'Close'}
        </Button>
        {items.length > 0 && (
          <Button color="teal" loading={importing} disabled={selected.size === 0} onClick={() => void importSelected()}>
            Add {selected.size} node{selected.size === 1 ? '' : 's'}
          </Button>
        )}
      </Group>
    </Stack>
  )
}

type ModalProps = {
  opened: boolean
  onClose: () => void
  serverId: string
  serverName?: string
  onImported?: () => void
}

/** Standalone "Scan for nodes" modal — used from the Servers list for an existing server. */
export function DiscoverNodesModal({ opened, onClose, serverId, serverName, onImported }: ModalProps) {
  return (
    <Modal
      {...blockProps('modal.discover-nodes')}
      opened={opened}
      onClose={onClose}
      title={
        <Group gap="xs">
          <IconSearch size={18} stroke={1.5} aria-hidden />
          <Text fw={600}>Scan {serverName || 'server'} for existing nodes</Text>
        </Group>
      }
      size="md"
      centered
    >
      {opened && serverId ? (
        <DiscoverNodesPanel serverId={serverId} onImported={onImported} onDone={onClose} />
      ) : null}
    </Modal>
  )
}
