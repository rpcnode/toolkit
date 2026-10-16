import { ActionIcon, Button, Group, Modal, Select, Stack, Text, TextInput, Tooltip } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconPencil } from '@tabler/icons-react'
import { useEffect, useMemo, useState } from 'react'
import { api, type Workload } from '../api'
import { isL2Network, l1ParentSpec } from '../lib/l1Parent'
import { l1ParentFromNetworksCatalog } from '../lib/networksCatalog'

export { isL2Network, l1ParentSpec } from '../lib/l1Parent'
export type { L1ParentSpec } from '../lib/l1Parent'

type Choice = { id: string; label: string; rpc: string; beacon: string }

type Probe = { ok: boolean; detail: string } | null

/** Host (and port) only — the path of a provider URL usually carries the API key. */
export function l1HostLabel(url: string): string {
  try {
    return new URL(url).host
  } catch {
    return url.replace(/^https?:\/\//, '').split('/')[0]
  }
}

function l1EnvFor(env: string): 'sepolia' | 'mainnet' {
  const e = (env || '').toLowerCase()
  return e === 'sepolia' || e === 'testnet' ? 'sepolia' : 'mainnet'
}

/**
 * Parent-chain (Ethereum L1) endpoints of an L2 node: shown in the node's right pane and editable at any time.
 * Saving writes install_options.l1_rpc / l1_beacon; the host unit is re-rendered on the next Start / Restart.
 */
export function NodeParentChain({
  workload,
  onChanged,
}: {
  workload: Workload | null
  onChanged?: () => void
}) {
  const nodeId = String(workload?.id || '').trim()
  const network = String(workload?.network || '').toLowerCase()
  const env = String(workload?.env || '').toLowerCase()
  const spec = l1ParentSpec(network)
  const defaults = l1ParentFromNetworksCatalog(network, env)
  const saved = workload?.install_options || {}
  const rpcSaved = String(saved.l1_rpc || '').trim()
  const beaconSaved = String(saved.l1_beacon || '').trim()

  const [open, setOpen] = useState(false)
  const [rpc, setRpc] = useState('')
  const [beacon, setBeacon] = useState('')
  const [choices, setChoices] = useState<Choice[]>([])
  const [probe, setProbe] = useState<Record<string, Probe>>({})
  const [busy, setBusy] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setRpc(rpcSaved || defaults?.rpc || '')
    setBeacon(beaconSaved || defaults?.beacon || '')
    setProbe({})
    if (spec.ws) {
      // Juno needs a WebSocket URL: the panel's Ethereum nodes are listed by their HTTP port, so only the public default is offered
      setChoices(
        defaults?.rpc
          ? [{ id: 'public', label: `Public · ${l1HostLabel(defaults.rpc)} (shared, rate-limited)`, rpc: defaults.rpc, beacon: '' }]
          : [],
      )
      return
    }
    let cancelled = false
    void api
      .networksEthereumNodes({
        env: l1EnvFor(env),
        status: 'active',
        server_id: workload?.server_id || undefined,
        network: spec.chain === 'arb' ? 'arb' : undefined,
      })
      .then((res) => {
        if (cancelled) return
        const list: Choice[] = []
        if (res.public?.rpc) {
          list.push({
            id: 'public',
            label: `Public · ${l1HostLabel(res.public.rpc)} (shared, pruned, rate-limited)`,
            rpc: res.public.rpc,
            beacon: res.public.beacon || '',
          })
        }
        for (const n of res.items || []) {
          list.push({
            id: `node:${n.id}`,
            label: `${n.name} · ${n.status} · ${n.same_host ? 'this host' : l1HostLabel(n.rpc)}`,
            rpc: n.rpc,
            beacon: n.beacon,
          })
        }
        setChoices(list)
      })
      .catch(() => {
        if (!cancelled) setChoices([])
      })
    return () => {
      cancelled = true
    }
  }, [open])

  const selected = useMemo(
    () => choices.find((c) => c.rpc === rpc.trim() && (!c.beacon || c.beacon === beacon.trim()))?.id ?? null,
    [choices, rpc, beacon],
  )

  if (!nodeId || !isL2Network(network)) return null

  const shownRpc = rpcSaved || defaults?.rpc || ''
  const isDefault = !rpcSaved || rpcSaved === defaults?.rpc

  async function test(kind: 'eth_rpc' | 'eth_ws' | 'beacon_genesis', url: string) {
    const endpoint = url.trim()
    if (!endpoint) {
      setProbe((p) => ({ ...p, [kind]: { ok: false, detail: 'URL is empty' } }))
      return
    }
    setBusy(kind)
    try {
      const res = await api.networksTestConnect({ kind, url: endpoint })
      setProbe((p) => ({
        ...p,
        [kind]: res.ok === false
          ? { ok: false, detail: res.message || res.error || 'connect failed' }
          : { ok: true, detail: res.detail || 'ok' },
      }))
    } catch (e) {
      setProbe((p) => ({ ...p, [kind]: { ok: false, detail: String((e as Error).message || e) } }))
    } finally {
      setBusy(null)
    }
  }

  async function save(restart: boolean) {
    const nextRpc = rpc.trim()
    const nextBeacon = beacon.trim()
    if (!nextRpc || (spec.beacon && !nextBeacon)) {
      notifications.show({
        color: 'red',
        message: spec.beacon ? 'Both the L1 RPC and the beacon URL are required' : 'The parent-chain RPC URL is required',
      })
      return
    }
    if (spec.ws && !/^wss?:\/\//i.test(nextRpc)) {
      notifications.show({ color: 'red', message: 'Juno needs a WebSocket endpoint: the URL must start with ws:// or wss://' })
      return
    }
    setBusy(restart ? 'restart' : 'save')
    try {
      // install_options is stored as one document: send the whole object back with the two URLs changed.
      const res = await api.workloadsSaveInstallOptions(nodeId, {
        install_options: spec.beacon ? { ...saved, l1_rpc: nextRpc, l1_beacon: nextBeacon } : { ...saved, l1_rpc: nextRpc },
      })
      if (res.ok === false) throw new Error(res.message || res.error || 'save failed')
      if (restart) {
        const started = await api.workloadsNodeProcessRestart(nodeId)
        if (started.ok === false) throw new Error(started.message || started.error || 'restart failed')
      }
      notifications.show({
        color: 'teal',
        message: restart ? 'Parent chain saved, node restarted' : 'Parent chain saved — restart the node to apply',
      })
      setOpen(false)
      onChanged?.()
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setBusy(null)
    }
  }

  const probeLine = (kind: string) =>
    probe[kind] ? (
      <Text size="xs" c={probe[kind]!.ok ? 'teal' : 'red'}>
        {probe[kind]!.ok ? '✓ ' : '✗ '}
        {probe[kind]!.detail}
      </Text>
    ) : null

  return (
    <>
      <div className="node-meta__row" title={`${spec.name} parent chain this node reads from`}>
        <span className="node-meta__label">{spec.short}</span>
        <span className="node-meta__value node-meta__value--wrap">
          <Group gap={6} wrap="nowrap" justify="space-between">
            <Text span fw={600} c={isDefault ? 'yellow.5' : 'gray.3'} className="mono" style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>
              {shownRpc ? l1HostLabel(shownRpc) : '—'}
              {isDefault && shownRpc ? ' (public)' : ''}
            </Text>
            <Tooltip label={spec.beacon ? 'Change the Ethereum L1 RPC / beacon' : `Change the ${spec.name} endpoint`}>
              <ActionIcon size="xs" variant="light" color="gray" aria-label="Edit L1 endpoints" onClick={() => setOpen(true)}>
                <IconPencil size={12} />
              </ActionIcon>
            </Tooltip>
          </Group>
        </span>
      </div>

      <Modal opened={open} onClose={() => setOpen(false)} title={`${spec.name} parent chain`} size="lg">
        <Stack gap="sm">
          <Text size="sm" c="dimmed">
            {spec.ws
              ? `This node verifies the chain against Ethereum ${l1EnvFor(env)} over a WebSocket.`
              : `This node reads its batches from ${spec.name}${spec.chain === 'arb' ? '' : ` ${l1EnvFor(env)}`}.`}{' '}
            Public endpoints prune old logs and rate-limit, which stalls the sync — use your own node or a keyed provider.
          </Text>
          {choices.length > 0 ? (
            <Select
              label="Pick"
              placeholder="Public or an active node of this panel"
              data={choices.map((c) => ({ value: c.id, label: c.label }))}
              value={selected}
              clearable
              onChange={(id) => {
                const c = choices.find((x) => x.id === id)
                if (!c) return
                setRpc(c.rpc)
                setBeacon(c.beacon || beacon)
                setProbe({})
              }}
            />
          ) : null}
          <TextInput
            label={spec.ws ? 'Ethereum L1 WebSocket' : spec.chain === 'arb' ? 'Arbitrum One RPC' : 'Ethereum L1 execution RPC'}
            placeholder={
              spec.ws
                ? 'wss://eth-mainnet.g.alchemy.com/v2/<key>'
                : spec.chain === 'arb'
                  ? 'https://arb-mainnet.g.alchemy.com/v2/<key> or http://127.0.0.1:8647'
                  : 'https://eth-sepolia.g.alchemy.com/v2/<key> or http://127.0.0.1:8545'
            }
            value={rpc}
            onChange={(e) => {
              setRpc(e.currentTarget.value)
              setProbe((p) => ({ ...p, eth_rpc: null, eth_ws: null }))
            }}
            rightSectionWidth={110}
            rightSection={
              <Button
                size="compact-xs"
                variant="light"
                loading={busy === (spec.ws ? 'eth_ws' : 'eth_rpc')}
                onClick={() => void test(spec.ws ? 'eth_ws' : 'eth_rpc', rpc)}
              >
                Test connect
              </Button>
            }
          />
          {probeLine(spec.ws ? 'eth_ws' : 'eth_rpc')}
          {spec.beacon ? (
          <>
          <TextInput
            label="Ethereum L1 beacon / blob API"
            placeholder="https://… or http://127.0.0.1:5053"
            value={beacon}
            onChange={(e) => {
              setBeacon(e.currentTarget.value)
              setProbe((p) => ({ ...p, beacon_genesis: null }))
            }}
            rightSectionWidth={110}
            rightSection={
              <Button size="compact-xs" variant="light" loading={busy === 'beacon_genesis'} onClick={() => void test('beacon_genesis', beacon)}>
                Test connect
              </Button>
            }
          />
          {probeLine('beacon_genesis')}
          </>
          ) : null}
          <Group justify="flex-end" gap="xs">
            <Button variant="default" onClick={() => setOpen(false)}>
              Cancel
            </Button>
            <Button variant="light" loading={busy === 'save'} disabled={busy != null} onClick={() => void save(false)}>
              Save
            </Button>
            <Button loading={busy === 'restart'} disabled={busy != null} onClick={() => void save(true)}>
              Save &amp; restart
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  )
}
