import { ActionIcon, Code, Group, Text, Tooltip } from '@mantine/core'
import { IconCopy } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import type { ReactNode } from 'react'
import type { Workload } from '../api'
import type { StatusPayload } from '../types'
import { copyText } from '../lib/copyText'
import { blockProps } from '../lib/blockId'
import { formatClientVersion } from '../lib/format'
import { maskHostInURL } from '../lib/maskHost'
import { clientUpdateClickable } from '../lib/nodeLifecycle'
import { NodeAgentVersion } from './AgentVersion'
import { NodeDiskSummary } from './NodeDiskSummary'
import { NodeLifecycleDates } from './NodeLifecycleDates'

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
  const outdated =
    (!!workload?.client_update_available || !!status?.client_update?.update_available) ||
    (!!ver && !!latest && ver !== latest)
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
  const hasDates = !!(
    workload?.created_at ||
    workload?.install_started_at ||
    workload?.synced_at ||
    status?.served_at ||
    status?.updated_at ||
    workload?.updated_at
  )

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

      {endpoint ? (
        <div className="node-meta__group">
          <MetaRow label="rpc" title="Fullnode Go RPC on the confirmed public_port">
            <FullnodeValue endpoint={endpoint} />
          </MetaRow>
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
