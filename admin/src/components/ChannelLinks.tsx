import { ActionIcon, Anchor, Badge, Code, Group, Stack, Text } from '@mantine/core'
import { IconCopy } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import type { PanelSettings, ServiceLink } from '../api'
import { copyText } from '../lib/copyText'
import { blockProps } from '../lib/blockId'

export function ChannelLinks({
  links,
  curl,
  scripts,
  panelScripts,
}: {
  links?: ServiceLink[]
  curl?: string
  scripts?: PanelSettings['scripts']
  panelScripts?: PanelSettings['panel_scripts']
}) {
  const rows = [
    scripts?.install && { id: 'install', label: 'Install agents', cmd: scripts.install },
    scripts?.update && { id: 'update', label: 'Update agents', cmd: scripts.update },
    scripts?.uninstall && { id: 'uninstall', label: 'Uninstall agents', cmd: scripts.uninstall },
  ].filter(Boolean) as { id: string; label: string; cmd: string }[]

  const panelRows = [
    panelScripts?.install && { id: 'p-install', label: 'Install server', cmd: panelScripts.install },
    panelScripts?.update && { id: 'p-update', label: 'Update server', cmd: panelScripts.update },
    panelScripts?.uninstall && { id: 'p-uninstall', label: 'Uninstall server', cmd: panelScripts.uninstall },
  ].filter(Boolean) as { id: string; label: string; cmd: string }[]

  if (!links?.length && !curl && !rows.length && !panelRows.length) return null

  async function copy(value: string, label: string) {
    try {
      await copyText(value)
      notifications.show({ color: 'teal', message: `Copied ${label}` })
    } catch {
      notifications.show({ color: 'red', message: 'Copy failed' })
    }
  }

  return (
    <Stack gap={10} {...blockProps('shared.channel-links')}>
      {links?.length ? (
        <Stack gap={6}>
          <Text size="sm" fw={600}>
            Running
          </Text>
          {links.map((l) => (
            <Group key={l.id} gap={8} wrap="nowrap" justify="space-between">
              <Group gap={8} wrap="nowrap" style={{ minWidth: 0 }}>
                <Badge size="xs" color={l.ok ? 'teal' : 'gray'} variant="light">
                  {l.ok ? 'up' : 'down'}
                </Badge>
                <Anchor href={l.url} target="_blank" rel="noopener noreferrer" size="sm" style={{ whiteSpace: 'nowrap' }}>
                  {l.label}
                </Anchor>
                <Code style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{l.url}</Code>
              </Group>
              <ActionIcon
                variant="subtle"
                color="gray"
                size="sm"
                aria-label={`Copy ${l.label}`}
                onClick={() => void copy(l.url, l.label)}
              >
                <IconCopy size={14} />
              </ActionIcon>
            </Group>
          ))}
        </Stack>
      ) : null}

      {rows.length ? (
        <Stack gap={6}>
          <Text size="sm" fw={600}>
            Host agents
          </Text>
          {rows.map((r) => (
            <Group key={r.id} gap={8} wrap="nowrap" justify="space-between">
              <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
                <Text size="xs" c="dimmed">
                  {r.label}
                </Text>
                <Code style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.cmd}</Code>
              </Stack>
              <ActionIcon variant="subtle" color="gray" size="sm" aria-label={`Copy ${r.label}`} onClick={() => void copy(r.cmd, r.label)}>
                <IconCopy size={14} />
              </ActionIcon>
            </Group>
          ))}
        </Stack>
      ) : curl ? (
        <Group gap={8} wrap="nowrap" justify="space-between">
          <Code style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{curl}</Code>
          <ActionIcon variant="subtle" color="gray" size="sm" aria-label="Copy install command" onClick={() => void copy(curl, 'curl')}>
            <IconCopy size={14} />
          </ActionIcon>
        </Group>
      ) : null}

      {panelRows.length ? (
        <Stack gap={6}>
          <Text size="sm" fw={600}>
            This server (repo)
          </Text>
          {panelRows.map((r) => (
            <Group key={r.id} gap={8} wrap="nowrap" justify="space-between">
              <Stack gap={2} style={{ minWidth: 0, flex: 1 }}>
                <Text size="xs" c="dimmed">
                  {r.label}
                </Text>
                <Code style={{ overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.cmd}</Code>
              </Stack>
              <ActionIcon variant="subtle" color="gray" size="sm" aria-label={`Copy ${r.label}`} onClick={() => void copy(r.cmd, r.label)}>
                <IconCopy size={14} />
              </ActionIcon>
            </Group>
          ))}
        </Stack>
      ) : null}
    </Stack>
  )
}
