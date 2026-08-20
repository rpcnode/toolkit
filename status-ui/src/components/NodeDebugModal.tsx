import {
  ActionIcon,
  Alert,
  Badge,
  Box,
  Button,
  Center,
  Code,
  Group,
  Loader,
  Modal,
  SegmentedControl,
  Stack,
  Text,
  Tooltip,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconCheck, IconCopy, IconRefresh } from '@tabler/icons-react'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { api, type NodeDebugFinding, type NodeDebugReport } from '../api'
import { copyText } from '../lib/copyText'

type Props = {
  opened: boolean
  onClose: () => void
  serverId: string
  network: string
  env: string
  title?: string
}

function findingColor(sev: string | undefined) {
  switch (sev) {
    case 'error':
      return 'red'
    case 'warn':
      return 'yellow'
    case 'ok':
      return 'teal'
    default:
      return 'gray'
  }
}

function reportText(rep: NodeDebugReport | null): string {
  if (!rep) return ''
  const lines: string[] = []
  lines.push(`debug ${rep.network || ''}/${rep.env || ''} · ${rep.collected_at || ''}`)
  lines.push(`errors=${rep.error_count || 0} warns=${rep.warn_count || 0}`)
  for (const f of rep.findings || []) {
    lines.push(`[${f.severity || '?'}] ${f.scope || ''} ${f.code || ''} — ${f.title || ''}`)
    if (f.detail) lines.push(`  ${f.detail}`)
    if (f.hint) lines.push(`  hint: ${f.hint}`)
  }
  if (rep.units?.length) {
    lines.push('units:')
    for (const u of rep.units) {
      lines.push(`  ${u.name} ${u.active || ''}/${u.sub || ''} result=${u.result || ''}`)
    }
  }
  if (rep.procs?.length) {
    lines.push('procs:')
    for (const p of rep.procs) lines.push(`  ${p}`)
  }
  for (const log of rep.logs || []) {
    lines.push(`--- ${log.label || log.id || 'log'} ${log.path || ''} ---`)
    lines.push((log.lines || []).join('\n'))
  }
  return lines.join('\n')
}

export function NodeDebugModal({ opened, onClose, serverId, network, env, title }: Props) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [report, setReport] = useState<NodeDebugReport | null>(null)
  const [logId, setLogId] = useState('')
  const [copied, setCopied] = useState(false)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const load = useCallback(async () => {
    if (!serverId || !network || !env) return
    setLoading(true)
    setError(null)
    try {
      const res = await api.workloadsDebug({ server_id: serverId, network, env })
      if (res.ok === false && (res.message || res.error)) {
        setReport(res)
        setError(res.message || res.error || 'debug failed')
        return
      }
      setReport(res)
      const logs = res.logs || []
      setLogId((prev) => {
        if (prev && logs.some((l) => (l.id || '') === prev)) return prev
        return logs[0]?.id || ''
      })
    } catch (e) {
      setReport(null)
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
    }
  }, [serverId, network, env])

  useEffect(() => {
    if (!opened) return
    void load()
  }, [opened, load])

  useEffect(
    () => () => {
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
    },
    [],
  )

  const findings = report?.findings || []
  const logs = report?.logs || []
  const active = useMemo(
    () => logs.find((l) => l.id === logId) || logs[0],
    [logs, logId],
  )
  const joined = (active?.lines || []).join('\n')

  function copyAll() {
    const text = reportText(report)
    if (!text) return
    void copyText(text)
      .then(() => {
        setCopied(true)
        if (copiedTimer.current) clearTimeout(copiedTimer.current)
        copiedTimer.current = setTimeout(() => setCopied(false), 1500)
        notifications.show({ color: 'teal', message: 'Debug report copied', autoClose: 1500 })
      })
      .catch(() => {
        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
      })
  }

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={title || `Debug · ${network}/${env}`}
      centered
      size="xl"
    >
      <Stack gap="sm">
        <Group justify="space-between" wrap="wrap">
          <Text size="xs" c="dimmed">
            Read-only host + {network} snapshot
            {report?.collected_at ? ` · ${report.collected_at}` : ''}
            {report ? ` · ${report.error_count || 0} errors · ${report.warn_count || 0} warns` : ''}
          </Text>
          <Group gap={6}>
            <Tooltip label={copied ? 'Copied' : 'Copy report'}>
              <ActionIcon
                size="sm"
                variant="light"
                color={copied ? 'teal' : 'gray'}
                disabled={!report}
                aria-label="Copy debug report"
                onClick={copyAll}
              >
                {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
              </ActionIcon>
            </Tooltip>
            <Button
              size="xs"
              variant="light"
              leftSection={<IconRefresh size={12} />}
              loading={loading}
              onClick={() => void load()}
            >
              Refresh
            </Button>
          </Group>
        </Group>

        {loading && !report ? (
          <Center mih={160}>
            <Loader size="sm" color="teal" />
          </Center>
        ) : error && !report?.findings?.length ? (
          <Text size="sm" c="red">
            {error}
          </Text>
        ) : (
          <>
            <Stack gap={8}>
              {findings.map((f: NodeDebugFinding, i) => (
                <Alert
                  key={`${f.code || 'f'}-${i}`}
                  color={findingColor(f.severity)}
                  title={f.title || f.code || 'Finding'}
                >
                  <Stack gap={4}>
                    <Group gap={6}>
                      {f.scope ? (
                        <Badge size="xs" variant="light" color="gray">
                          {f.scope}
                        </Badge>
                      ) : null}
                      {f.code ? (
                        <Badge size="xs" variant="outline" color="gray">
                          {f.code}
                        </Badge>
                      ) : null}
                    </Group>
                    {f.detail ? (
                      <Text size="sm" className="mono" style={{ whiteSpace: 'pre-wrap' }}>
                        {f.detail}
                      </Text>
                    ) : null}
                    {f.hint ? (
                      <Text size="xs" c="dimmed">
                        {f.hint}
                      </Text>
                    ) : null}
                  </Stack>
                </Alert>
              ))}
            </Stack>

            {report?.units && report.units.length > 0 ? (
              <Box>
                <Text size="xs" c="dimmed" mb={4}>
                  Units
                </Text>
                <Code block className="mono" style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
                  {report.units
                    .map((u) => {
                      const rest = [u.nrestarts ? `restarts=${u.nrestarts}` : '', u.result || '']
                        .filter(Boolean)
                        .join(' ')
                      return `${u.name}  ${u.active || '—'} / ${u.sub || '—'}${rest ? `  ${rest}` : ''}`
                    })
                    .join('\n')}
                </Code>
              </Box>
            ) : null}

            {report?.procs && report.procs.length > 0 ? (
              <Box>
                <Text size="xs" c="dimmed" mb={4}>
                  Matching processes
                </Text>
                <Code block className="mono" style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>
                  {report.procs.join('\n')}
                </Code>
              </Box>
            ) : null}

            {logs.length > 0 ? (
              <>
                <SegmentedControl
                  fullWidth
                  size="xs"
                  value={active?.id || logId}
                  onChange={setLogId}
                  data={logs.map((l) => ({
                    value: l.id || l.label || 'log',
                    label: l.label || l.id || 'log',
                  }))}
                  styles={{
                    root: { flexWrap: 'wrap', height: 'auto' },
                    label: { whiteSpace: 'normal', lineHeight: 1.2, paddingBlock: 6 },
                  }}
                />
                {active?.path ? (
                  <Text size="xs" c="dimmed" className="mono">
                    {active.path}
                    {active.note ? ` · ${active.note}` : ''}
                  </Text>
                ) : null}
                <Box style={{ maxHeight: 280, overflow: 'auto' }}>
                  <Code block className="mono" style={{ whiteSpace: 'pre-wrap', fontSize: 12 }}>
                    {joined || '(empty)'}
                  </Code>
                </Box>
              </>
            ) : null}
          </>
        )}
      </Stack>
    </Modal>
  )
}
