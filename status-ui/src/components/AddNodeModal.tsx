import {
  Alert,
  Button,
  Group,
  Modal,
  Select,
  Stack,
  Stepper,
  TextInput,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useEffect, useMemo, useState } from 'react'
import { api, type RegistryNode, type Workload } from '../api'
import { formatDiskFreeTotal } from '../lib/format'
import {
  ENVS_BY_NETWORK,
  NETWORK_OPTIONS,
  networkOneEnvPerHost,
} from '../lib/networksCatalog'
import { navigate } from '../lib/router'

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

const NETWORK_SELECT_DATA = [...NETWORK_OPTIONS].sort((a, b) =>
  a.label.localeCompare(b.label, undefined, { sensitivity: 'base' }),
)

export function AddNodeModal({ opened, onClose, onAdded }: Props) {
  const [step, setStep] = useState(0)
  const [servers, setServers] = useState<RegistryNode[]>([])
  const [workloads, setWorkloads] = useState<Workload[]>([])
  const [network, setNetwork] = useState<string | null>(null)
  const [env, setEnv] = useState<string | null>(null)
  const [serverId, setServerId] = useState<string | null>(null)
  const [name, setName] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const envOptions = network ? ENVS_BY_NETWORK[network] || [] : []

  useEffect(() => {
    if (!opened) return
    setStep(0)
    setNetwork(null)
    setEnv(null)
    setName('')
    setError(null)
    setBusy(false)
    void Promise.all([api.registryList(), api.workloadsList()]).then(([r, w]) => {
      const items = [...(r.items || [])].sort((a, b) =>
        String(a.name || a.id).localeCompare(String(b.name || b.id), undefined, {
          sensitivity: 'base',
        }),
      )
      setServers(items)
      setWorkloads(w.items || [])
      if (items[0]?.id) setServerId(items[0].id)
    })
  }, [opened])

  useEffect(() => {
    if (!network) {
      setEnv(null)
      return
    }
    const list = ENVS_BY_NETWORK[network] || []
    if (!list.some((e) => e.value === env)) {
      setEnv(list[0]?.value || null)
    }
  }, [network, env])

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
    const diskOf = (s: RegistryNode) => formatDiskFreeTotal(s.metrics)
    const rows = servers.map((s) => {
      const base = s.name || s.id
      const disk = diskOf(s)
      if (!network || !env) {
        return {
          value: s.id,
          label: `${base} · ${disk}`,
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
      if (dup) {
        return {
          value: s.id,
          label: `${base} · ${disk} · already has ${network}/${env}`,
          disabled: false,
        }
      }
      return {
        value: s.id,
        label: other
          ? `${base} · ${disk} · blocked (${network}/${other.env} already)`
          : `${base} · ${disk}`,
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

  async function registerNode() {
    if (!serverId || !network || !env) {
      setError('Pick network, server and env')
      return
    }
    if (duplicateBlocked && occupiedSameEnv) {
      const st = String(occupiedSameEnv.status || '').toLowerCase()
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
    if (envBlocked && occupiedSameNetwork) {
      setError(
        `${network}/${occupiedSameNetwork.env} already on this server — only one Hyperliquid env per host`,
      )
      return
    }
    setBusy(true)
    setError(null)
    try {
      const existing = await api.workloadsList().catch(() => null)
      const hit = existing?.items?.find(
        (w) => w.server_id === serverId && sameNetEnv(w, network, env),
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

      // Panel → tip plan persists catalog ports; Install checks free + provisions.
      const res = await api.workloadsUpsert({
        server_id: serverId,
        network,
        env,
        name: name.trim() || undefined,
      })
      if (!res.ok || !res.item) throw new Error(res.message || res.error || 'register failed')

      notifications.show({
        color: 'teal',
        message: `${network}/${env} added — ports from tip, Install next`,
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
  }

  return (
    <Modal opened={opened} onClose={onClose} title="Add node" size="lg">
      <Stack gap="md">
        <Stepper active={step} onStepClick={setStep} size="sm" allowNextStepsSelect={false}>
          <Stepper.Step label="Network" description="chain">
            <Stack gap="sm" mt="md">
              <Select
                label="Network"
                data={NETWORK_SELECT_DATA}
                value={network}
                onChange={setNetwork}
                clearable
                searchable
                nothingFoundMessage="No network"
                placeholder="Select network"
              />
              <Group justify="flex-end">
                <Button onClick={() => setStep(1)} disabled={!network}>
                  Next
                </Button>
              </Group>
            </Stack>
          </Stepper.Step>

          <Stepper.Step label="Env" description="mainnet / test">
            <Stack gap="sm" mt="md">
              <Select
                label="Environment"
                data={envSelectData}
                value={env}
                onChange={setEnv}
                allowDeselect={false}
                disabled={!network}
                placeholder={network ? 'Select environment' : 'Select network first'}
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
              />
              <Group justify="space-between">
                <Button variant="default" onClick={() => setStep(0)}>
                  Back
                </Button>
                <Button onClick={() => setStep(2)} disabled={!env || envBlocked}>
                  Next
                </Button>
              </Group>
            </Stack>
          </Stepper.Step>

          <Stepper.Step label="Server" description="host agent">
            <Stack gap="sm" mt="md">
              {servers.length === 0 ? (
                <Alert color="yellow">No servers registered. Add a server first.</Alert>
              ) : (
                <Select
                  label="Server"
                  description="Disk free / total from tip host metrics"
                  data={serverOptions}
                  value={serverId}
                  onChange={setServerId}
                  allowDeselect={false}
                  searchable
                  nothingFoundMessage="No server"
                  placeholder="Search servers…"
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
                  disabled={!serverId || !network || !env || envBlocked || duplicateBlocked}
                  onClick={() => void registerNode()}
                >
                  Add node
                </Button>
              </Group>
            </Stack>
          </Stepper.Step>
        </Stepper>
      </Stack>
    </Modal>
  )
}
