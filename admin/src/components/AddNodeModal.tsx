import {
  Alert,
  Button,
  Group,
  Modal,
  Stack,
  Stepper,
  Text,
  TextInput,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  api,
  type ClientRow,
  type RegistryNode,
  type Workload,
} from '../api'
import { SearchableKeyboardList } from './SearchableKeyboardList'
import { isModEnter } from '../lib/keyboard'
import { hasClientNetworkEnvs, networksWithClients } from '../lib/clientNetworks'
import {
  envsForNetwork,
  networkOneEnvPerHost,
  networkOptions,
  useNetworksCatalog,
} from '../lib/networksCatalog'
import { navigate } from '../lib/router'
import { blockProps } from '../lib/blockId'

type Props = {
  opened: boolean
  onClose: () => void
  onAdded?: () => void
}

function sameNetEnv(w: Workload, network: string, env: string) {
  return (
    String(w.network || '').toLowerCase() === network.toLowerCase() &&
    String(w.env || '') === env
  )
}

export function AddNodeModal({ opened, onClose, onAdded }: Props) {
  const networks = useNetworksCatalog()
  const [step, setStep] = useState(0)
  const [servers, setServers] = useState<RegistryNode[]>([])
  const [workloads, setWorkloads] = useState<Workload[]>([])
  const [clientRows, setClientRows] = useState<ClientRow[]>([])
  const [clientsLoading, setClientsLoading] = useState(false)
  const [clientsError, setClientsError] = useState<string | null>(null)
  const [network, setNetwork] = useState<string | null>(null)
  const [env, setEnv] = useState<string | null>(null)
  const [serverId, setServerId] = useState<string | null>(null)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const availableNetworks = useMemo(
    () => networksWithClients(networks, clientRows),
    [networks, clientRows],
  )
  const NETWORK_SELECT_DATA = [...networkOptions(availableNetworks)].sort((a, b) =>
    a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }),
  )
  const noClients = !clientsLoading && !hasClientNetworkEnvs(clientRows)
  const envOptions = network ? envsForNetwork(network, availableNetworks) : []

  useEffect(() => {
    if (!opened) return
    setStep(0)
    setNetwork(null)
    setEnv(null)
    setName('')
    setError(null)
    setBusy(false)
    setClientRows([])
    setClientsError(null)
    setClientsLoading(true)
    let cancelled = false
    void (async () => {
      try {
        const [r, w] = await Promise.all([
          api.registryList().catch(() => ({ items: [] })),
          api.workloadsList().catch(() => ({ items: [] })),
        ])
        if (cancelled) return
        const items = [...(r.items || [])].sort((a, b) =>
          String(a.name || a.id).localeCompare(String(b.name || b.id), undefined, {
            sensitivity: 'base',
          }),
        )
        setServers(items)
        setWorkloads(w.items || [])
        if (items[0]?.id) setServerId(items[0].id)
      } catch (e) {
        if (cancelled) return
        setError(String((e as Error).message || e))
      }
      try {
        const c = await api.clients()
        if (cancelled) return
        setClientRows(c.rows || [])
      } catch (e) {
        if (cancelled) return
        setClientsError(String((e as Error).message || e))
        setClientRows([])
      } finally {
        if (!cancelled) setClientsLoading(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [opened])

  useEffect(() => {
    if (!network) {
      setEnv(null)
      return
    }
    const list = envsForNetwork(network, availableNetworks)
    if (!list.some((e) => e.value === env)) {
      setEnv(list[0]?.value || null)
    }
  }, [network, env, availableNetworks])

  /** Exact server + network + env — any status blocks Add. */
  const occupiedSameEnv = useMemo(() => {
    if (!serverId || !network || !env) return null
    return (
      workloads.find((w) => w.server_id === serverId && sameNetEnv(w, network, env)) || null
    )
  }, [workloads, serverId, network, env])

  const occupiedSameNetwork = useMemo(() => {
    if (!serverId || !network || !networkOneEnvPerHost(network)) return null
    return (
      workloads.find(
        (w) =>
          w.server_id === serverId &&
          String(w.network || '').toLowerCase() === network.toLowerCase() &&
          w.status !== 'removing',
      ) || null
    )
  }, [workloads, serverId, network])

  const envBlocked =
    !!occupiedSameNetwork &&
    !!env &&
    occupiedSameNetwork.env !== env &&
    !!network &&
    networkOneEnvPerHost(network)

  const duplicateBlocked = !!occupiedSameEnv

  const envSelectData = useMemo(() => {
    return envOptions.map((e) => {
      const blocked =
        !!network &&
        !!occupiedSameNetwork &&
        occupiedSameNetwork.env !== e.value &&
        networkOneEnvPerHost(network)
      return {
        value: e.value,
        label: blocked
          ? `${e.label} (blocked — ${network}/${occupiedSameNetwork!.env} on server)`
          : e.label,
        disabled: blocked,
      }
    })
  }, [envOptions, occupiedSameNetwork, network])

  const serverOptions = useMemo(() => {
    const rows = servers.map((s) => {
      const label = s.name || s.id
      if (!network || !env) {
        return {
          value: s.id,
          label,
          disabled: false,
        }
      }
      const dup = workloads.find((w) => w.server_id === s.id && sameNetEnv(w, network, env))
      const other =
        !dup && networkOneEnvPerHost(network)
          ? workloads.find(
              (w) =>
                w.server_id === s.id &&
                String(w.network || '').toLowerCase() === network.toLowerCase() &&
                w.env !== env &&
                w.status !== 'removing',
            )
          : null
      return {
        value: s.id,
        label,
        disabled: !!other,
      }
    })
    return rows.sort((a, b) =>
      a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }),
    )
  }, [servers, workloads, network, env])

  function openNode(id: string) {
    onClose()
    onAdded?.()
    navigate({ name: 'node', id })
  }

  const pairAllowed =
    !!network &&
    !!env &&
    availableNetworks.some(
      (n) =>
        n.id === network.toLowerCase() &&
        n.envs.some((e) => e.toLowerCase() === env.toLowerCase()),
    )

  const canSubmit =
    !!serverId &&
    !!network &&
    !!env &&
    pairAllowed &&
    !envBlocked &&
    !duplicateBlocked &&
    !busy &&
    !noClients

  const registerNode = useCallback(async (override?: { serverId?: string }) => {
    const sid = override?.serverId || serverId
    if (!sid || !network || !env) {
      setError('Pick network, server and env')
      return
    }
    if (!pairAllowed) {
      setError('Add a client for this network and env first')
      return
    }
    const occupiedEnv =
      workloads.find((w) => w.server_id === sid && sameNetEnv(w, network, env)) || null
    const occupiedNet = networkOneEnvPerHost(network)
      ? workloads.find(
          (w) =>
            w.server_id === sid &&
            String(w.network || '').toLowerCase() === network.toLowerCase() &&
            w.status !== 'removing',
        ) || null
      : null
    if (occupiedEnv) {
      const st = String(occupiedEnv.status || '').toLowerCase()
      const msg =
        st === 'removing'
          ? `${network}/${env} remove still in progress — open the node and retry Remove`
          : st === 'remove_error'
            ? `${network}/${env} remove failed — open the node and retry Remove`
            : `${network}/${env} already exists on this server`
      setError(msg)
      notifications.show({ color: 'orange', message: msg })
      return
    }
    if (occupiedNet && occupiedNet.env !== env) {
      setError(
        `${network}/${occupiedNet.env} already on this server — only one Hyperliquid env per host`,
      )
      return
    }
    setBusy(true)
    setError(null)
    try {
      const existing = await api.workloadsList().catch(() => null)
      const hit = existing?.items?.find(
        (w) => w.server_id === sid && sameNetEnv(w, network, env),
      )
      if (hit?.id) {
        const st = String(hit.status || '').toLowerCase()
        const msg =
          st === 'removing'
            ? `${network}/${env} remove still in progress — open the node and retry Remove`
            : st === 'remove_error'
              ? `${network}/${env} remove failed — open the node and retry Remove`
              : `${network}/${env} already exists on this server`
        setError(msg)
        notifications.show({ color: 'orange', message: msg })
        setWorkloads(existing?.items || workloads)
        return
      }

      const res = await api.workloadsUpsert({
        server_id: sid,
        network,
        env,
        name: name.trim() || undefined,
      })
      if (!res.ok || !res.item) throw new Error(res.message || res.error || 'register failed')

      notifications.show({
        color: 'teal',
        message: `${network}/${env} added — awaiting ports`,
      })
      openNode(res.item.id)
    } catch (e) {
      const msg = String((e as Error).message || e)
      setError(msg)
      if (/already exists/i.test(msg)) {
        notifications.show({ color: 'orange', message: msg })
        const again = await api.workloadsList().catch(() => null)
        if (again?.items) setWorkloads(again.items)
      }
    } finally {
      setBusy(false)
    }
  }, [serverId, network, env, name, pairAllowed, workloads])

  useEffect(() => {
    if (!opened || busy) return
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        if (step > 0) {
          e.preventDefault()
          e.stopPropagation()
          setStep((s) => s - 1)
        }
        return
      }
      if (noClients) return
      // List search handles plain Enter; keep Ctrl/⌘↵ as a global shortcut.
      if (!isModEnter(e)) return
      e.preventDefault()
      if (step === 0 && network) setStep(1)
      else if (step === 1 && env && !envBlocked) setStep(2)
      else if (step === 2 && canSubmit) void registerNode()
    }
    window.addEventListener('keydown', onKey, true)
    return () => window.removeEventListener('keydown', onKey, true)
  }, [opened, busy, step, network, env, envBlocked, canSubmit, registerNode, noClients])

  return (
    <Modal
      {...blockProps('modal.add-node')}
      opened={opened}
      onClose={onClose}
      title="Add node"
      size="lg"
      closeOnEscape={step === 0}
    >
      <Stack gap="md">
        {clientsLoading ? (
          <Text size="sm" c="dimmed">
            Loading clients…
          </Text>
        ) : clientsError ? (
          <Alert color="red" title="Clients unavailable">
            {clientsError}
          </Alert>
        ) : noClients ? (
          <Alert color="yellow" title="No clients">
            Add a client first. Add node only lists networks and environments that already appear on
            Clients.
            <Group mt="sm">
              <Button
                color="teal"
                onClick={() => {
                  onClose()
                  navigate({ name: 'clients' })
                }}
              >
                Go to Clients
              </Button>
            </Group>
          </Alert>
        ) : (
        <Stepper active={step} onStepClick={setStep} size="sm" allowNextStepsSelect={false}>
          <Stepper.Step label="Network" description="chain">
            <Stack gap="sm" mt="md" {...blockProps('modal.add-node.step.network')}>
              <SearchableKeyboardList
                key={opened ? 'network-open' : 'network-closed'}
                label="Network"
                items={NETWORK_SELECT_DATA}
                value={network}
                onChange={setNetwork}
                onEnterOnSelected={() => setStep(1)}
                searchPlaceholder="Search networks…"
                nothingFoundMessage="No network"
                listAriaLabel="Networks"
                autoFocus={step === 0 && opened}
                autoSelectFirst
              />
              <Group justify="flex-end">
                <Button onClick={() => setStep(1)} disabled={!network}>
                  Next <Text span size="xs" c="dimmed" ml={6}>
                    ↵
                  </Text>
                </Button>
              </Group>
            </Stack>
          </Stepper.Step>

          <Stepper.Step label="Env" description="mainnet / test">
            <Stack gap="sm" mt="md" {...blockProps('modal.add-node.step.env')}>
              <SearchableKeyboardList
                key={`env-${network || 'none'}`}
                label="Environment"
                items={envSelectData}
                value={env}
                onChange={setEnv}
                onEnterOnSelected={(v) => {
                  const row = envSelectData.find((e) => e.value === v)
                  if (row && !row.disabled) setStep(2)
                }}
                searchPlaceholder="Search environments…"
                nothingFoundMessage="No environment"
                listAriaLabel="Environments"
                autoFocus={step === 1 && opened}
                autoSelectFirst
                disabled={!network}
              />
              {network && networkOneEnvPerHost(network) && (
                <Alert color="yellow" title="One environment per host">
                  Hyperliquid allows only one env (mainnet or testnet) per server — the hl-node
                  client panics if another hl-node process is already running.
                </Alert>
              )}
              <TextInput
                label="Display name (optional)"
                placeholder={network && env ? `${network}-${env}` : 'optional'}
                value={name}
                onChange={(e) => setName(e.currentTarget.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && env && !envBlocked) {
                    e.preventDefault()
                    setStep(2)
                  }
                }}
              />
              <Group justify="space-between">
                <Button variant="default" onClick={() => setStep(0)}>
                  Back
                </Button>
                <Button onClick={() => setStep(2)} disabled={!env || envBlocked}>
                  Next <Text span size="xs" c="dimmed" ml={6}>
                    ↵
                  </Text>
                </Button>
              </Group>
            </Stack>
          </Stepper.Step>

          <Stepper.Step label="Server" description="host agent">
            <Stack gap="sm" mt="md" {...blockProps('modal.add-node.step.server')}>
              {servers.length === 0 ? (
                <Alert color="yellow">No servers registered. Add a server first.</Alert>
              ) : (
                <SearchableKeyboardList
                  key={opened ? 'server-open' : 'server-closed'}
                  label="Server"
                  items={serverOptions}
                  value={serverId}
                  onChange={setServerId}
                  onEnterOnSelected={(v) => {
                    void registerNode({ serverId: v })
                  }}
                  searchPlaceholder="Search servers…"
                  nothingFoundMessage="No server"
                  listAriaLabel="Servers"
                  autoFocus={step === 2 && opened}
                  autoSelectFirst
                />
              )}
              {duplicateBlocked && occupiedSameEnv && network && env && (
                <Alert color="orange" title="Already exists">
                  {network}/{env} already exists on this server.
                </Alert>
              )}
              {envBlocked && occupiedSameNetwork && !duplicateBlocked && network && (
                <Alert color="red" title="Environment blocked">
                  {network}/{occupiedSameNetwork.env} is already on this server. Remove it first, or
                  pick another server.
                </Alert>
              )}
              {error && step === 2 && (
                <Alert color="red" title="Error">
                  {error}
                </Alert>
              )}
              <Group justify="space-between">
                <Button variant="default" onClick={() => setStep(1)} disabled={busy}>
                  Back
                </Button>
                <Button
                  color="teal"
                  loading={busy}
                  disabled={!canSubmit}
                  onClick={() => void registerNode()}
                >
                  Add node{' '}
                  <Text span size="xs" c="dimmed" ml={6}>
                    ↵
                  </Text>
                </Button>
              </Group>
            </Stack>
          </Stepper.Step>
        </Stepper>
        )}
      </Stack>
    </Modal>
  )
}
