import { Select } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useEffect, useState } from 'react'
import { api, type ProxyItem } from '../api'

const DIRECT = ''

type Props = {
  nodeId: string
  /** Host the node runs on: the chosen proxy is test-connected from its agent. */
  serverId?: string | null
  /** Current `proxy_id` from the node row ("" = direct). */
  value?: string | null
  /** Shown after a change on a node that already runs (the host picks it up on Start / Restart). */
  applyHint?: string
  label?: string
  description?: string
  size?: 'xs' | 'sm'
  onSaved?: (proxyId: string) => void
}

/** Pick the node's proxy from Settings → Proxies; saves at once. */
export function NodeProxySelect({ nodeId, serverId, value, applyHint, label, description, size = 'sm', onSaved }: Props) {
  const [items, setItems] = useState<ProxyItem[]>([])
  const [current, setCurrent] = useState(value || DIRECT)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setCurrent(value || DIRECT)
  }, [value])

  useEffect(() => {
    let cancelled = false
    api
      .proxies()
      .then((res) => {
        if (!cancelled) setItems(res.items || [])
      })
      .catch(() => {
        if (!cancelled) setItems([])
      })
    return () => {
      cancelled = true
    }
  }, [])

  const data = [
    { value: DIRECT, label: 'Direct (no proxy)' },
    ...items.map((p) => ({ value: p.id, label: p.name })),
  ]
  // A proxy id that is no longer in the list still shows instead of silently reading "Direct".
  if (current && !items.some((p) => p.id === current) && items.length > 0) {
    data.push({ value: current, label: 'Removed proxy' })
  }

  async function change(next: string | null) {
    const id = next || DIRECT
    if (id === current || !nodeId) return
    setBusy(true)
    try {
      await api.setNodeProxy(nodeId, id)
      setCurrent(id)
      onSaved?.(id)
      notifications.show({
        color: 'teal',
        message: applyHint || (id ? 'Proxy saved' : 'Proxy removed — direct connection'),
      })
      // Saved either way (operator's call), but say at once when the server cannot reach it.
      if (id && serverId) {
        const check = await api.testProxy({ id, url: '', server_id: serverId }).catch((e) => ({
          ok: false,
          message: String((e as Error).message || e),
        }))
        if (!check.ok) {
          notifications.show({
            color: 'orange',
            autoClose: 12000,
            title: 'Proxy not reachable from this server',
            message: check.message || 'The host could not connect through it',
          })
        }
      }
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Select
      size={size}
      label={label}
      description={description}
      data={data}
      value={current}
      onChange={(v) => void change(v)}
      disabled={busy || !nodeId}
      allowDeselect={false}
      comboboxProps={{ withinPortal: true }}
    />
  )
}
