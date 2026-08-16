import {
  ActionIcon,
  Box,
  Card,
  Center,
  Code,
  Group,
  Loader,
  Modal,
  Stack,
  Text,
  Title,
  Tooltip,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconCheck, IconCopy } from '@tabler/icons-react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { StatusPayload } from '../types'
import { copyText } from '../lib/copyText'
import { resolveEnv, resolveNetwork } from '../lib/network'

/** Host log paths from agent (path/paths), with network/env fallback for older agents. */
export function agentLogPaths(status: StatusPayload | null | undefined): string[] {
  if (!status) return []
  const fromArr = status.logs?.paths
  if (Array.isArray(fromArr) && fromArr.length > 0) {
    return fromArr.map(String).filter(Boolean)
  }
  if (status.logs?.path) {
    return [String(status.logs.path)]
  }
  const net = resolveNetwork(status)
  const env = resolveEnv(status)
  if (!net || !env) return []
  const unit = `${net}-${env}.service`
  const paths = [`journalctl -u ${unit}`, `/var/lib/rpcnode/${net}-${env}/sync.log`]
  if (net === 'ton') {
    // Prefer real tails — ton-<env>.service is an empty oneshot wrapper.
    return [
      `/var/log/ton/${env}/bootstrap.log`,
      `/etc/ton/${env}/sync-progress.log`,
      'journalctl -u validator.service',
      'journalctl -u ton_http_api.service',
    ]
  }
  if (net === 'solana') {
    paths.push(`/data/solana/${env}/solana-${env}.log`)
  }
  if (net === 'tron') {
    return [
      `/opt/tron/${env}/logs/tron.log`,
      `journalctl -u tron-${env}.service`,
      `/var/log/tron/${env}-snapshot.log`,
    ]
  }
  return paths
}

/** Resolve agent-owned log lines (never invent progress in the panel). */
export function agentLogLines(status: StatusPayload | null | undefined): string[] {
  if (!status) return []
  const fromLogs = status.logs?.lines
  if (Array.isArray(fromLogs) && fromLogs.length > 0) {
    return fromLogs.map(String)
  }
  const fromSync = status.sync?.log_tail
  if (Array.isArray(fromSync) && fromSync.length > 0) {
    return fromSync.map(String)
  }
  const fromSnap = status.snapshot?.log_tail
  if (Array.isArray(fromSnap) && fromSnap.length > 0) {
    return fromSnap.map(String)
  }
  return []
}

function earlyLifecycleForLogs(status: StatusPayload): boolean {
  const phase = (status.ui_phase || status.lifecycle?.phase || '').toLowerCase()
  const cur = (
    status.lifecycle?.current_step_id ||
    status.lifecycle?.current ||
    ''
  ).toLowerCase()
  const ns = (status.node_status || status.lifecycle?.node_status || '').toLowerCase()
  const keys = [phase, cur, ns]
  for (const k of keys) {
    if (
      k === 'install' ||
      k === 'snapshot' ||
      k === 'start' ||
      k === 'ports' ||
      k === 'setup' ||
      k.includes('snapshot') ||
      k.includes('starting') ||
      k.includes('download')
    ) {
      return true
    }
  }
  return false
}

function stillSyncing(status: StatusPayload): boolean {
  const sync = status.sync
  if (sync?.ibd) return true
  const vp = sync?.verification_pct ?? sync?.verificationprogress
  if (typeof vp === 'number' && vp > 0 && vp < 99.9) return true
  const phase = (status.ui_phase || status.lifecycle?.phase || '').toLowerCase()
  const ns = (status.node_status || status.lifecycle?.node_status || '').toLowerCase()
  if (phase === 'run' || ns === 'syncing') return true
  const label = (status.lifecycle?.label || '').toLowerCase()
  const detail = (status.lifecycle?.detail || sync?.detail || '').toLowerCase()
  if (label.includes('syncing') || label.includes('catching')) return true
  if (detail.includes('syncing') || detail.includes('catching up')) return true
  return false
}

function nodeCaughtUp(status: StatusPayload): boolean {
  if (earlyLifecycleForLogs(status) || stillSyncing(status)) return false
  const phase = (status.ui_phase || status.lifecycle?.phase || '').toLowerCase()
  const ns = (status.node_status || status.lifecycle?.node_status || '').toLowerCase()
  const label = (status.lifecycle?.label || '').toLowerCase()
  if (phase === 'healthy' || ns === 'running' || ns === 'healthy') return true
  if (label.startsWith('healthy')) return true
  // Agent source "done" after install/sync samples — node is past bootstrap.
  const src = (status.logs?.source || '').toLowerCase()
  if (src === 'done' && (status.rpc?.reachable || status.rpc?.http_ok)) return true
  return false
}

/** Hide verbose install/sync journal once the node is healthy and caught up. */
export function showAgentLogsPanel(status: StatusPayload | null | undefined): boolean {
  if (!status) return false
  if (earlyLifecycleForLogs(status)) return true
  if (stillSyncing(status)) return true
  if (nodeCaughtUp(status)) return false
  // Mid-run with lines but not yet healthy — keep visible.
  return agentLogLines(status).length > 0
}

function scrollBoxToBottom(el: HTMLElement | null) {
  if (!el) return
  el.scrollTop = el.scrollHeight
  // Second paint: Code/pre layout can grow after first scroll assignment.
  requestAnimationFrame(() => {
    el.scrollTop = el.scrollHeight
  })
}

type LogsBodyProps = {
  status: StatusPayload | null | undefined
  loading?: boolean
  maxHeight?: number
  /** When true, show empty wait line during early lifecycle (inline card). */
  showEmptyWhileSetup?: boolean
}

/** Shared log body — used by inline card and modal. */
export function AgentLogsBody({
  status,
  loading,
  maxHeight = 320,
  showEmptyWhileSetup,
}: LogsBodyProps) {
  const lines = agentLogLines(status)
  const scroller = useRef<HTMLDivElement | null>(null)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [copied, setCopied] = useState(false)
  const joined = lines.join('\n')
  const showEmpty =
    !!showEmptyWhileSetup && !!status && earlyLifecycleForLogs(status) && lines.length === 0
  const title = status?.logs?.title || 'Logs'
  const source = status?.logs?.source || status?.sync?.network || status?.snapshot?.phase || ''
  const logPaths = agentLogPaths(status)
  const cachedNote = status?.cached || status?.agent_reachable === false
  const canCopy = lines.length > 0

  useLayoutEffect(() => {
    if (loading || lines.length === 0) return
    scrollBoxToBottom(scroller.current)
  }, [joined, loading, lines.length])

  useEffect(() => {
    return () => {
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
    }
  }, [])

  function copyLogs() {
    if (!canCopy) return

    void copyText(joined)
      .then(() => {
        setCopied(true)
        if (copiedTimer.current) clearTimeout(copiedTimer.current)
        copiedTimer.current = setTimeout(() => setCopied(false), 1500)
        notifications.show({
          color: 'teal',
          message: 'Logs copied',
          autoClose: 2000,
        })
      })
      .catch(() => {
        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
      })
  }

  if (loading) {
    return (
      <Center mih={120}>
        <Loader size="sm" color="teal" />
      </Center>
    )
  }

  return (
    <>
      <Group justify="space-between" mb="sm" wrap="wrap">
        <Group gap="xs">
          <Title order={4} c="dimmed" tt="uppercase" size="xs">
            {title}
          </Title>
          <Tooltip label={copied ? 'Copied' : 'Copy logs'}>
            <ActionIcon
              size="sm"
              variant="light"
              color={copied ? 'teal' : 'gray'}
              aria-label="Copy logs"
              disabled={!canCopy}
              onClick={copyLogs}
            >
              {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
            </ActionIcon>
          </Tooltip>
        </Group>
        {source ? (
          <Text size="xs" c="dimmed">
            agent · {source}
            {cachedNote ? ' · last known' : ''}
          </Text>
        ) : cachedNote ? (
          <Text size="xs" c="dimmed">
            last known
          </Text>
        ) : null}
      </Group>
      {logPaths.length > 0 ? (
        <Stack gap={4} mb="sm">
          <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
            Path{logPaths.length > 1 ? 's' : ''} on host
          </Text>
          {logPaths.map((p) => (
            <Group key={p} gap={6} wrap="nowrap" align="flex-start">
              <Code
                className="mono"
                style={{
                  flex: 1,
                  minWidth: 0,
                  wordBreak: 'break-all',
                  whiteSpace: 'pre-wrap',
                  fontSize: 12,
                }}
              >
                {p}
              </Code>
              <Tooltip label="Copy path">
                <ActionIcon
                  size="sm"
                  variant="subtle"
                  color="gray"
                  aria-label={`Copy path ${p}`}
                  onClick={() => {
                    void copyText(p)
                      .then(() => {
                        notifications.show({
                          color: 'teal',
                          message: 'Path copied',
                          autoClose: 1500,
                        })
                      })
                      .catch(() => {
                        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
                      })
                  }}
                >
                  <IconCopy size={14} />
                </ActionIcon>
              </Tooltip>
            </Group>
          ))}
        </Stack>
      ) : null}
      <Box ref={scroller} style={{ maxHeight, overflow: 'auto' }}>
        {lines.length > 0 ? (
          <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
            {joined}
          </Code>
        ) : showEmpty ? (
          <Text size="sm" c="dimmed" className="mono">
            Waiting for agent log lines (install / snapshot / start)…
          </Text>
        ) : (
          <Text size="sm" c="dimmed" className="mono">
            No log lines from agent yet.
          </Text>
        )}
      </Box>
    </>
  )
}

/** Renders status.logs / sync.log_tail / snapshot.log_tail. Shows during early lifecycle even if empty. */
export function AgentLogsPanel({ status }: { status: StatusPayload }) {
  const lines = agentLogLines(status)
  const showEmpty = earlyLifecycleForLogs(status)
  const visible = showAgentLogsPanel(status) && !(lines.length === 0 && !showEmpty)

  if (!visible) return null

  return (
    <Card>
      <AgentLogsBody status={status} showEmptyWhileSetup maxHeight={320} />
    </Card>
  )
}

type LogsModalProps = {
  opened: boolean
  onClose: () => void
  status: StatusPayload | null | undefined
  loading?: boolean
  title?: string
}

/** Modal to inspect agent logs anytime (incl. after Healthy when inline panel is hidden). */
export function AgentLogsModal({
  opened,
  onClose,
  status,
  loading,
  title = 'Logs',
}: LogsModalProps) {
  return (
    <Modal opened={opened} onClose={onClose} title={title} centered size="xl">
      <AgentLogsBody status={status} loading={loading} maxHeight={480} showEmptyWhileSetup />
    </Modal>
  )
}
