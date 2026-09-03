import {
  Accordion,
  ActionIcon,
  Badge,
  Button,
  Code,
  Group,
  Popover,
  Stack,
  Text,
  ThemeIcon,
  Tooltip,
} from '@mantine/core'
import { IconAlertTriangle, IconCheck, IconHelp, IconX } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import type { CheckedCatalogPort } from '../../../../api'
import { PORTS_CHECK_HELP, busyListenWhoisCommands } from '../../utils'

export function WizardStepHelp({ title, text }: { title: string; text: string }) {
  return (
    <Popover width={320} position="bottom-start" withArrow shadow="md">
      <Popover.Target>
        <ActionIcon
          variant="subtle"
          color="gray"
          size="sm"
          aria-label={title}
          className="node-install-wizard__help"
        >
          <IconHelp size={16} stroke={1.75} />
        </ActionIcon>
      </Popover.Target>
      <Popover.Dropdown>
        <Text size="sm" c="dimmed">
          {text}
        </Text>
      </Popover.Dropdown>
    </Popover>
  )
}

export function portCheckStatus(p: {
  bind?: string
  external?: boolean
  reach?: string
}): 'ok' | 'fail' | 'pending' {
  if (p.bind === 'busy') return 'fail'
  if (p.external && p.reach === 'filtered') return 'fail'
  if (p.bind === 'free') {
    if (p.external) return p.reach === 'reachable' ? 'ok' : 'pending'
    return 'ok'
  }
  return 'pending'
}

export function PortStatusMark({ status }: { status: 'ok' | 'fail' | 'pending' }) {
  if (status === 'ok') {
    return (
      <ThemeIcon size={22} radius="xl" color="teal" variant="light" aria-label="OK">
        <IconCheck size={14} stroke={2.5} />
      </ThemeIcon>
    )
  }
  if (status === 'fail') {
    return (
      <ThemeIcon size={22} radius="xl" color="red" variant="light" aria-label="Not OK">
        <IconX size={14} stroke={2.5} />
      </ThemeIcon>
    )
  }
  return (
    <ThemeIcon size={22} radius="xl" color="gray" variant="light" aria-label="Pending">
      <Text size="xs" c="dimmed" fw={700}>
        …
      </Text>
    </ThemeIcon>
  )
}

export function PortCatalogAccordion({
  ports,
  status,
  onKill,
}: {
  ports: CheckedCatalogPort[]
  status: 'ok' | 'fail' | 'pending'
  onKill: (p: CheckedCatalogPort) => void
}) {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (status === 'fail') setOpen(true)
  }, [status])

  return (
    <Accordion
      variant="separated"
      radius={0}
      chevronPosition="left"
      value={open ? 'catalog' : null}
      onChange={(v) => setOpen(v === 'catalog')}
      classNames={{
        root: 'node-install-wizard__ports',
        item: 'node-install-wizard__port-item',
        control: 'node-install-wizard__port-control',
        panel: 'node-install-wizard__port-panel',
        content: 'node-install-wizard__port-content',
        chevron: 'node-install-wizard__port-chevron',
      }}
    >
      <Accordion.Item value="catalog">
        <Accordion.Control>
          <Group justify="space-between" wrap="nowrap" w="100%" pr={4} gap="sm">
            <Group gap={6} align="center" wrap="nowrap" miw={0} style={{ flex: 1, minWidth: 0 }}>
              <Text size="sm" fw={600} lineClamp={1}>
                Check ports
              </Text>
              <div
                onClick={(e) => e.stopPropagation()}
                onKeyDown={(e) => e.stopPropagation()}
                role="presentation"
              >
                <WizardStepHelp title="About port check" text={PORTS_CHECK_HELP} />
              </div>
            </Group>
            <div
              onClick={(e) => e.stopPropagation()}
              onKeyDown={(e) => e.stopPropagation()}
              role="presentation"
            >
              <PortStatusMark status={status} />
            </div>
          </Group>
        </Accordion.Control>
        <Accordion.Panel>
          <Stack gap={0}>
            {ports.map((p) => (
              <PortCatalogRow
                key={`${p.role}-${p.port}`}
                port={p}
              />
            ))}
          </Stack>
        </Accordion.Panel>
      </Accordion.Item>
    </Accordion>
  )
}

export function PortCatalogRow({
  port: p,
  onKill,
}: {
  port: CheckedCatalogPort
  onKill?: () => void
}) {
  const label = p.label || p.role
  return (
    <div className="node-install-wizard__port-row">
      <Group justify="space-between" wrap="nowrap" gap="xs" mb={4}>
        <Text size="xs" fw={500} lineClamp={1} style={{ flex: 1, minWidth: 0 }}>
          {label}
        </Text>
        <Code className="mono node-install-wizard__port-num">
          {p.port != null && p.port > 0 ? String(p.port) : '—'}
        </Code>
      </Group>
      <PortLineDetails port={p} onKill={onKill} />
    </div>
  )
}

export function PortLineDetails({
  port: p,
  onKill,
}: {
  port: CheckedCatalogPort
  onKill?: () => void
}) {
  const label = p.label || p.role
  return (
    <PortLine
      label={label}
      port={p.port}
      external={!!p.external}
      bind={p.bind}
      holder={p.holder}
      pid={p.pid}
      comm={p.comm}
      cmdline={p.cmdline}
      unit={p.unit}
      killable={p.killable}
      killBlocked={p.kill_blocked}
      reach={p.reach}
      onKill={onKill}
      compact
    />
  )
}

export function PortLine({
  label,
  port,
  external,
  bind,
  holder,
  pid,
  comm,
  cmdline,
  unit,
  killable,
  killBlocked,
  reach,
  onKill,
  compact = false,
}: {
  label: string
  port?: number
  external: boolean
  bind?: string
  holder?: string
  pid?: string
  comm?: string
  cmdline?: string
  unit?: string
  killable?: boolean
  killBlocked?: string
  reach?: string
  onKill?: () => void
  compact?: boolean
}) {
  const bindBusy = bind === 'busy'
  const who = [comm, pid ? `pid ${pid}` : '', unit, holder].filter(Boolean).join(' · ')
  const reachColor =
    reach === 'filtered' ? 'red' : reach === 'reachable' ? 'teal' : 'gray'
  return (
    <Stack gap={4}>
    {!compact ? (
    <Group justify="space-between" wrap="wrap">
      <Text size="sm">{label}</Text>
      <Group gap={6}>
        <Badge color={external ? 'cyan' : 'gray'} variant="light" size="sm">
          {external ? 'external' : 'internal'}
        </Badge>
        {bind ? (
          <Badge color={bindBusy ? 'red' : 'teal'} variant="light" size="sm">
            {bindBusy ? `busy${who ? ` · ${who}` : ''}` : 'free'}
          </Badge>
        ) : null}
        {bindBusy && onKill ? (
          <Tooltip
            label={
              killable === true
                ? 'Kill process'
                : killBlocked || 'Update agent to inspect/kill this process'
            }
          >
            <span>
              <Button
                size="compact-xs"
                color="red"
                variant="light"
                disabled={killable !== true}
                leftSection={<IconX size={12} />}
                onClick={onKill}
              >
                Kill
              </Button>
            </span>
          </Tooltip>
        ) : null}
        {external && reach && reach !== 'n/a' ? (
          <Badge color={reachColor} variant="light" size="sm">
            {reach}
          </Badge>
        ) : null}
        <Code className="mono">{port != null && port > 0 ? String(port) : '—'}</Code>
      </Group>
    </Group>
    ) : (
    <Stack gap={3}>
      <Group gap={4} wrap="wrap">
        <Badge color={external ? 'cyan' : 'gray'} variant="light" size="xs">
          {external ? 'external' : 'internal'}
        </Badge>
        {bind ? (
          <Badge color={bindBusy ? 'red' : 'teal'} variant="light" size="xs">
            {bindBusy ? 'busy' : 'free'}
          </Badge>
        ) : null}
        {external && reach && reach !== 'n/a' ? (
          <Badge color={reachColor} variant="light" size="xs">
            {reach}
          </Badge>
        ) : null}
        {bindBusy && onKill ? (
          <Tooltip
            label={
              killable === true
                ? 'Kill process'
                : killBlocked || 'Update agent to inspect/kill this process'
            }
          >
            <span>
              <Button
                size="compact-xs"
                color="red"
                variant="light"
                disabled={killable !== true}
                leftSection={<IconX size={11} />}
                onClick={onKill}
              >
                Kill
              </Button>
            </span>
          </Tooltip>
        ) : null}
      </Group>
      {bindBusy && who ? (
        <Text size="xs" c="dimmed" lineClamp={1}>
          {who}
        </Text>
      ) : null}
    </Stack>
    )}
    {bindBusy && cmdline ? (
      <Text size="xs" c="dimmed" className="mono" lineClamp={2}>
        {cmdline}
      </Text>
    ) : null}
    </Stack>
  )
}

/**
 * NODE SETUP until panel SQLite `status` is Active (or stopped/online after setup).
 * Height / tip / connect.ready must not drive this — only `workload.status`.
 */
