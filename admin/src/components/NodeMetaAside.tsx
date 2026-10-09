import { ActionIcon, Code, Group, Text, Tooltip } from '@mantine/core'
import { IconCopy, IconEye, IconEyeOff } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useEffect, useState, type ReactNode } from 'react'
import { api, type Workload } from '../api'
import type { StatusPayload } from '../types'
import { copyText } from '../lib/copyText'
import { blockProps } from '../lib/blockId'
import { formatClientVersion } from '../lib/format'
import { maskHostInURL } from '../lib/maskHost'
import { getNetworks } from '../lib/networksCatalog'
import { clientUpdateClickable } from '../lib/nodeLifecycle'
import { NodeAgentVersion } from './AgentVersion'
import { NodeDiskSummary } from './NodeDiskSummary'
import { NodeLifecycleDates } from './NodeLifecycleDates'

const RPC_USER_PASSWORD_NETWORKS = new Set(['dash', 'ltc', 'doge', 'bch'])

type Props = {
  workload: Workload | null
  status: StatusPayload | null
  /** Server row label (name / id) — the host this node runs on. */
  serverLabel?: string | null
  /** Tip agent version of that host, for the leaf-vs-tip gap. */
  tipAgentVersion?: string
  /** Agent-owned phase; gates the client-update click target only. */
  phase?: string | null
  /** Already masked on render; the full value stays for copy. */
  fullnodeEndpoint?: string | null
  onClientUpdate: () => void
}

function MetaRow({
  label,
  title,
  wrap,
  children,
}: {
  label: string
  title?: string
  wrap?: boolean
  children: ReactNode
}) {
  return (
    <div className="node-meta__row" title={title}>
      <span className="node-meta__label">{label}</span>
      <span className={`node-meta__value${wrap ? ' node-meta__value--wrap' : ''}`}>{children}</span>
    </div>
  )
}

/**
 * Client version from the node (`client_version`). Newer pin version comes from
 * Clients (`client_latest` on the API is filled from the pin). Click opens update
 * even while the node is running — the host stops it as part of the job.
 */
function ClientVersionValue({
  workload,
  status,
  phase,
  onClientUpdate,
}: Pick<Props, 'workload' | 'status' | 'phase' | 'onClientUpdate'>) {
  const ver = formatClientVersion(
    status?.client_version ||
      status?.rpc?.client_version ||
      status?.rpc?.version ||
      workload?.client_version ||
      '',
  )
  const latest = formatClientVersion(workload?.client_latest || status?.client_update?.latest || '')
  // Prefer panel pin compare (`client_update_available`); string ≠ is only a fallback when the
  // flag is absent — multi-program VERSION must not compare lighthouse to geth.
  const flag =
    typeof workload?.client_update_available === 'boolean'
      ? workload.client_update_available
      : typeof status?.client_update?.update_available === 'boolean'
        ? status.client_update.update_available
        : null
  const outdated = flag === true || (flag == null && !!ver && !!latest && ver !== latest)
  const color = !ver ? 'gray.3' : outdated ? 'orange.4' : 'teal.4'
  const canClick = clientUpdateClickable(phase)
  const openUpdate = canClick ? onClientUpdate : undefined
  const clickStyle = canClick
    ? { cursor: 'pointer', textDecoration: 'underline', textUnderlineOffset: 2 }
    : undefined

  return (
    <Text
      span
      fw={600}
      c={color}
      className="mono"
      style={clickStyle}
      title={
        !canClick
          ? 'Client update already in progress'
          : outdated
            ? `Update available → ${latest || 'newer'} (click to confirm)`
            : ver
              ? 'Update client (click to confirm)'
              : 'Client version unknown — click to update from Clients pin'
      }
      onClick={openUpdate}
    >
      {ver || '—'}
      {outdated && latest ? ` → ${latest}` : ''}
    </Text>
  )
}

function FullnodeValue({ endpoint, label = 'Fullnode' }: { endpoint: string; label?: string }) {
  return (
    <Group gap={4} wrap="nowrap" style={{ minWidth: 0 }}>
      <Code
        className="mono"
        title="Hidden — use copy"
        style={{
          flex: '1 1 auto',
          minWidth: 0,
          fontSize: 11,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {maskHostInURL(endpoint)}
      </Code>
      <Tooltip label={`Copy confirmed ${label} endpoint`}>
        <ActionIcon
          size="xs"
          variant="light"
          color="gray"
          aria-label={`Copy ${label} endpoint`}
          style={{ flexShrink: 0 }}
          onClick={() => {
            void copyText(endpoint)
              .then(() => {
                notifications.show({
                  color: 'teal',
                  message: `${label} endpoint copied`,
                  autoClose: 2000,
                })
              })
              .catch(() => {
                notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
              })
          }}
        >
          <IconCopy size={12} />
        </ActionIcon>
      </Tooltip>
    </Group>
  )
}

function PathValue({ path }: { path: string }) {
  return (
    <Group gap={4} wrap="nowrap" style={{ minWidth: 0 }}>
      <Code
        className="mono"
        style={{
          flex: '1 1 auto',
          minWidth: 0,
          fontSize: 11,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {path}
      </Code>
      <Tooltip label="Copy config path">
        <ActionIcon
          size="xs"
          variant="light"
          color="gray"
          aria-label="Copy config path"
          style={{ flexShrink: 0 }}
          onClick={() => {
            void copyText(path)
              .then(() => {
                notifications.show({ color: 'teal', message: 'Config path copied', autoClose: 2000 })
              })
              .catch(() => {
                notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
              })
          }}
        >
          <IconCopy size={12} />
        </ActionIcon>
      </Tooltip>
    </Group>
  )
}

function SecretValue({ value, label }: { value: string; label: string }) {
  const [show, setShow] = useState(false)
  const masked = value.length <= 4 ? '••••' : `${'•'.repeat(Math.min(12, value.length - 2))}${value.slice(-2)}`
  return (
    <Group gap={4} wrap="nowrap" style={{ minWidth: 0 }}>
      <Code
        className="mono"
        style={{
          flex: '1 1 auto',
          minWidth: 0,
          fontSize: 11,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {show ? value : masked}
      </Code>
      <Tooltip label={show ? 'Hide' : 'Show'}>
        <ActionIcon
          size="xs"
          variant="light"
          color="gray"
          aria-label={show ? `Hide ${label}` : `Show ${label}`}
          style={{ flexShrink: 0 }}
          onClick={() => setShow((v) => !v)}
        >
          {show ? <IconEyeOff size={12} /> : <IconEye size={12} />}
        </ActionIcon>
      </Tooltip>
      <Tooltip label={`Copy ${label}`}>
        <ActionIcon
          size="xs"
          variant="light"
          color="gray"
          aria-label={`Copy ${label}`}
          style={{ flexShrink: 0 }}
          onClick={() => {
            void copyText(value)
              .then(() => {
                notifications.show({ color: 'teal', message: `${label} copied`, autoClose: 2000 })
              })
              .catch(() => {
                notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
              })
          }}
        >
          <IconCopy size={12} />
        </ActionIcon>
      </Tooltip>
    </Group>
  )
}

function CopyableValue({ value, label }: { value: string; label: string }) {
  return (
    <Group gap={4} wrap="nowrap" style={{ minWidth: 0 }}>
      <Code
        className="mono"
        style={{
          flex: '1 1 auto',
          minWidth: 0,
          fontSize: 11,
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
      >
        {value}
      </Code>
      <Tooltip label={`Copy ${label}`}>
        <ActionIcon
          size="xs"
          variant="light"
          color="gray"
          aria-label={`Copy ${label}`}
          style={{ flexShrink: 0 }}
          onClick={() => {
            void copyText(value)
              .then(() => {
                notifications.show({ color: 'teal', message: `${label} copied`, autoClose: 2000 })
              })
              .catch(() => {
                notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
              })
          }}
        >
          <IconCopy size={12} />
        </ActionIcon>
      </Tooltip>
    </Group>
  )
}

/**
 * Human label for the wizard Node type choice(s) from install_options.
 * Uses catalog titles when available; skips snapshot / L1 parent URLs.
 */
function nodeTypeDisplay(workload: Workload | null): string | null {
  const opts = workload?.install_options
  if (!opts || typeof opts !== 'object') return null

  const skip = new Set(['snapshot', 'l1_rpc', 'l1_beacon'])
  const net = (workload?.network || '').toLowerCase()
  const env = (workload?.env || '').toLowerCase()
  const catalog = getNetworks().find((n) => n.id === net)
  const detail = (catalog?.env_details || []).find((e) => e.id === env)
  const groups = (detail?.install_options || []).filter((g) => !skip.has(g.id))

  const parts: string[] = []
  if (groups.length > 0) {
    for (const g of groups) {
      const raw = String(opts[g.id] || '').trim()
      if (!raw) continue
      const choice = (g.choices || []).find((c) => c.id === raw)
      parts.push(choice?.title || raw)
    }
  } else {
    for (const [key, value] of Object.entries(opts)) {
      if (skip.has(key)) continue
      const raw = String(value || '').trim()
      if (!raw) continue
      // Avoid dumping tunables (numbers / paths) as "type".
      if (/^(LimitNOFILE|rpc_threads|.*_(port|url|path))$/i.test(key)) continue
      if (/^\d+$/.test(raw) || raw.includes('/') || raw.startsWith('http')) continue
      parts.push(raw)
    }
  }
  return parts.length ? parts.join(' · ') : null
}

/**
 * NodeMetaAside — identity / timeline / storage / endpoint of one node as a
 * dense label-value column in the shell's right pane.
 *
 * As one header line it spanned the page and still truncated on the fields the
 * operator actually reads (mount paths, endpoint), and it pushed the title row
 * two lines down on every node. Every group hides itself when the node has
 * nothing to show yet, so an `awaiting_ports` row is short rather than a column
 * of dashes.
 */
export function NodeMetaAside({
  workload,
  status,
  serverLabel,
  tipAgentVersion,
  phase,
  fullnodeEndpoint,
  onClientUpdate,
}: Props) {
  const endpoint = String(fullnodeEndpoint || '').trim()
  const configPath = String(status?.config?.path || '').trim()
  const nodeType = nodeTypeDisplay(workload)
  const hasDates = !!(
    workload?.created_at ||
    workload?.install_started_at ||
    workload?.synced_at ||
    status?.served_at ||
    status?.updated_at ||
    workload?.updated_at
  )
  const network = (workload?.network || '').toLowerCase()
  const needsRpcAuth = RPC_USER_PASSWORD_NETWORKS.has(network)
  const nodeId = String(workload?.id || '').trim()
  const [rpcUser, setRpcUser] = useState('')
  const [rpcPassword, setRpcPassword] = useState('')

  useEffect(() => {
    if (!needsRpcAuth || !nodeId) {
      setRpcUser('')
      setRpcPassword('')
      return
    }
    let cancelled = false
    void api
      .workloadsNodeRpcAuth(nodeId)
      .then((res) => {
        if (cancelled) return
        if (res.ok === false || res.error === 'not_applicable' || res.error === 'not_found') {
          setRpcUser('')
          setRpcPassword('')
          return
        }
        setRpcUser(String(res.user || '').trim())
        setRpcPassword(String(res.password || '').trim())
      })
      .catch(() => {
        if (!cancelled) {
          setRpcUser('')
          setRpcPassword('')
        }
      })
    return () => {
      cancelled = true
    }
  }, [needsRpcAuth, nodeId])

  return (
    <div className="node-meta" {...blockProps('node.detail.meta-panel')}>
      <div className="node-meta__group">
        {workload?.name ? (
          <MetaRow label="node">
            <Text span fw={600} c="gray.3">
              {workload.name}
            </Text>
          </MetaRow>
        ) : null}
        {nodeType ? (
          <MetaRow label="type" title="Node type chosen in the install wizard">
            <Text span fw={600} c="gray.3">
              {nodeType}
            </Text>
          </MetaRow>
        ) : null}
        {workload?.status ? (
          <MetaRow label="status" title="Panel node status from API (item.status)">
            <Text span fw={600} c="gray.3" className="mono">
              {workload.status}
            </Text>
          </MetaRow>
        ) : null}
        {serverLabel ? (
          <MetaRow label="server">
            <Text span fw={600} c="gray.3">
              {serverLabel}
            </Text>
          </MetaRow>
        ) : null}
        <MetaRow label="client" wrap>
          <ClientVersionValue
            workload={workload}
            status={status}
            phase={phase}
            onClientUpdate={onClientUpdate}
          />
        </MetaRow>
        <MetaRow label="agent" wrap>
          <NodeAgentVersion status={status} tipVersion={tipAgentVersion} hideLabel />
        </MetaRow>
      </div>

      {hasDates ? (
        <div className="node-meta__group">
          <NodeLifecycleDates
            added={workload?.created_at}
            install={workload?.install_started_at}
            synced={workload?.synced_at}
            updated={status?.served_at || status?.updated_at || workload?.updated_at}
          />
        </div>
      ) : null}

      <NodeDiskSummary layout={workload?.disk_layout} rows />

      {configPath ? (
        <div className="node-meta__group">
          <MetaRow label="config" title="Full path to the node's own config file on the disk it was installed on">
            <PathValue path={configPath} />
          </MetaRow>
        </div>
      ) : null}

      {endpoint || rpcUser || rpcPassword ? (
        <div className="node-meta__group">
          {endpoint ? (
            <MetaRow label="rpc" title="Fullnode JSON-RPC endpoint (confirmed public_port)">
              <FullnodeValue endpoint={endpoint} />
            </MetaRow>
          ) : null}
          {rpcUser ? (
            <MetaRow label="rpcuser" title="JSON-RPC Basic auth user (required for direct fullnode access)">
              <CopyableValue value={rpcUser} label="rpcuser" />
            </MetaRow>
          ) : null}
          {rpcPassword ? (
            <MetaRow label="rpcpassword" title="JSON-RPC Basic auth password (required for direct fullnode access)">
              <SecretValue value={rpcPassword} label="rpcpassword" />
            </MetaRow>
          ) : null}
          {(status?.connect?.endpoints || []).map((ep) => (
            <MetaRow
              key={ep.id}
              label={ep.id}
              title={`${ep.label} — same public port, different upstream (catalog-driven)`}
            >
              <FullnodeValue endpoint={ep.url} label={ep.label} />
            </MetaRow>
          ))}
        </div>
      ) : null}
    </div>
  )
}
