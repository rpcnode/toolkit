import {
  Alert,
  Box,
  Code,
  Group,
  Loader,
  Progress,
  Stack,
  Text,
  Title,
} from '@mantine/core'
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { api, type AgentLogStream } from '../api'
import { formatSyncPct, pct } from '../lib/format'
import { resolveCurrentStep } from '../lib/nodeLifecycle'
import type { StatusPayload } from '../types'
import { agentLogLines } from './AgentLogsPanel'
import { LifecycleStepper } from './LifecycleStepper'
import { pickDefaultLogStream, sortLogStreams, streamRelevantToNode } from '../lib/serverLogStreams'

type Props = {
  status: StatusPayload | null
  statusReady?: boolean
  network?: string
  env?: string
  needsSnapshot?: boolean
  serverId?: string
  serverName?: string
  wizardLines?: string[]
  busy: boolean
  countdown?: number
}

function streamRelevant(s: AgentLogStream, network?: string, env?: string): boolean {
  return streamRelevantToNode(s, network, env)
}

function preferInstallLines(lines: string[], network?: string): string[] {
  const net = (network || '').toLowerCase()
  const keys = [
    'download',
    'provision',
    'install',
    'fetch',
    'wget',
    'curl',
    'apt',
    'compile',
    'extract',
    'tarball',
    'unpack',
    'manifest',
    'get ',
    net,
  ]
  const hit = lines.filter((ln) => {
    const low = ln.toLowerCase()
    return keys.some((k) => k && low.includes(k))
  })
  return hit.length > 0 ? hit : lines
}

function activityCopy(
  status: StatusPayload | null,
  busy: boolean,
  countdown: number,
): { title: string; detail: string } {
  const cur = resolveCurrentStep(status?.lifecycle)
  if (countdown > 0) {
    return {
      title: `Waiting ${countdown}s for the leaf agent`,
      detail: 'Units were written. Status check starts after this short settle.',
    }
  }
  if (cur?.title) {
    return {
      title: cur.headline || cur.title,
      detail:
        cur.detail ||
        status?.lifecycle?.detail ||
        'Host is still working — download and compile can take several minutes.',
    }
  }
  if (busy) {
    return {
      title: 'Provisioning on the host',
      detail:
        'Downloading the client, writing units, starting leaf agents. Large tarballs and compile can take several minutes — live log below.',
    }
  }
  return {
    title: 'Install activity',
    detail: status?.lifecycle?.detail || '',
  }
}

export function InstallActivityPanel({
  status,
  statusReady = false,
  network,
  env,
  needsSnapshot,
  serverId,
  serverName,
  wizardLines = [],
  busy,
  countdown = 0,
}: Props) {
  const [hostLines, setHostLines] = useState<string[]>([])
  const [hostPath, setHostPath] = useState('')
  const [logError, setLogError] = useState<string | null>(null)
  const scroller = useRef<HTMLDivElement | null>(null)
  const copy = activityCopy(status, busy, countdown)
  const cur = resolveCurrentStep(status?.lifecycle)
  const bar =
    cur?.pct != null
      ? pct(cur.pct as number | string)
      : status?.snapshot?.pct != null
        ? pct(status.snapshot.pct)
        : null

  const loadLogs = useCallback(async () => {
    if (!serverId) return
    try {
      const res = await api.serverLogs(serverId, { lines: 120 })
      const streams = sortLogStreams(
        (res.streams || []).filter((s) => streamRelevant(s, network, env)),
        network,
      )
      const preferId = pickDefaultLogStream(streams, network)
      const install = streams.find((s) => s.id === preferId) || streams[0]
      const raw = install?.lines || []
      setHostPath(install?.path || '')
      setHostLines(preferInstallLines(raw, network).slice(-60))
      setLogError(null)
    } catch (e) {
      // Keep the last tail, but never look like "no activity" when the tip
      // refused us (missing / wrong Server agent key → tip HTTP 401).
      setLogError(e instanceof Error ? e.message : String(e))
    }
  }, [serverId, network, env])

  useEffect(() => {
    if (!busy) return
    void loadLogs()
    const id = window.setInterval(() => void loadLogs(), 3000)
    return () => window.clearInterval(id)
  }, [busy, loadLogs])

  const installLines = Array.isArray(status?.install_log?.lines)
    ? status.install_log.lines.filter(Boolean)
    : []
  const agentLines = agentLogLines(status)
  const joined = useMemo(() => {
    const blocks: string[] = []
    if (wizardLines.length) blocks.push(wizardLines.slice(-20).join('\n'))
    if (installLines.length) blocks.push(installLines.slice(-30).join('\n'))
    if (agentLines.length) blocks.push(agentLines.slice(-20).join('\n'))
    if (hostLines.length) blocks.push(hostLines.join('\n'))
    return blocks.join('\n')
  }, [wizardLines, installLines, agentLines, hostLines])

  useLayoutEffect(() => {
    const el = scroller.current
    if (!el || !joined) return
    el.scrollTop = el.scrollHeight
    requestAnimationFrame(() => {
      el.scrollTop = el.scrollHeight
    })
  }, [joined])

  if (!busy && !joined && !logError) return null

  return (
    <Stack gap="sm">
      <Group justify="space-between" align="flex-start" wrap="nowrap">
        <div>
          <Group gap="xs" mb={4}>
            {busy ? <Loader size="xs" color="cyan" /> : null}
            <Title order={4}>{copy.title}</Title>
          </Group>
          <Text size="sm" c="dimmed">
            {copy.detail}
            {serverName ? ` · ${serverName}` : ''}
          </Text>
        </div>
        {bar != null ? (
          <Text fw={700} size="lg" className="mono">
            {formatSyncPct(bar)}
          </Text>
        ) : busy ? (
          <Text size="sm" c="dimmed">
            working…
          </Text>
        ) : null}
      </Group>
      <Progress
        value={bar ?? (busy ? 8 : 0)}
        animated={busy}
        striped={busy}
        size="lg"
      />
      <LifecycleStepper
        status={status}
        lifecycle={status?.lifecycle}
        network={network}
        env={env}
        needsSnapshot={needsSnapshot}
        ready={statusReady}
        hidePortsStep
      />
      <Text size="xs" c="dimmed">
        {hostPath ||
          status?.install_log?.path ||
          (network ? `/var/log/rpcnode/${network}-install.log` : 'install log')}
        {' · '}
        live tail
      </Text>
      {logError ? (
        <Alert color="orange" variant="light" title="Cannot read host logs">
          <Stack gap={4}>
            <Text size="sm">{logError}</Text>
            <Text size="xs" c="dimmed">
              Install itself keeps running on the host. A 401 here means the Server agent key is
              wrong or missing — fix it in Servers → Edit (tip /etc/rpcnode/agent.token).
            </Text>
          </Stack>
        </Alert>
      ) : null}
      <Box ref={scroller} style={{ maxHeight: 280, overflow: 'auto' }}>
        {joined ? (
          <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
            {joined}
          </Code>
        ) : (
          <Text size="sm" c="dimmed" className="mono">
            Waiting for the first host line (download / provision / install)…
          </Text>
        )}
      </Box>
    </Stack>
  )
}
