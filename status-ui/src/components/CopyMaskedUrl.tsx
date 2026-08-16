import { ActionIcon, Code, Group, Text, Tooltip } from '@mantine/core'
import { IconCheck, IconCopy } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useEffect, useRef, useState } from 'react'
import { copyText } from '../lib/copyText'
import { maskHostInURL } from '../lib/maskHost'

type Props = {
  url: string
  /** Label above the value (Servers card). */
  label?: string
  size?: 'xs' | 'sm'
  /** Compact one-line for node cards. */
  compact?: boolean
  copyMessage?: string
  className?: string
}

export function CopyMaskedUrl({
  url,
  label,
  size = 'xs',
  compact = false,
  copyMessage = 'URL copied',
  className,
}: Props) {
  const [copied, setCopied] = useState(false)
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(
    () => () => {
      if (timerRef.current) clearTimeout(timerRef.current)
    },
    [],
  )

  const raw = String(url || '').trim()
  if (!raw) {
    return (
      <Text size={size} c="dimmed">
        —
      </Text>
    )
  }
  const masked = maskHostInURL(raw)

  function onCopy(e?: { stopPropagation: () => void }) {
    e?.stopPropagation()
    void copyText(raw)
      .then(() => {
        setCopied(true)
        notifications.show({ color: 'teal', message: copyMessage, autoClose: 1500 })
        if (timerRef.current) clearTimeout(timerRef.current)
        timerRef.current = setTimeout(() => setCopied(false), 1500)
      })
      .catch(() => {
        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
      })
  }

  if (compact) {
    return (
      <Group gap={4} wrap="nowrap" className={className} onClick={(e) => e.stopPropagation()}>
        <Text size={size} c="dimmed" className="mono" lineClamp={1} title="Hidden — use copy" style={{ minWidth: 0 }}>
          {masked}
        </Text>
        <Tooltip label={copied ? 'Copied' : 'Copy full URL'}>
          <ActionIcon
            size="xs"
            variant="subtle"
            color={copied ? 'teal' : 'gray'}
            aria-label="Copy URL"
            onClick={onCopy}
          >
            {copied ? <IconCheck size={12} /> : <IconCopy size={12} />}
          </ActionIcon>
        </Tooltip>
      </Group>
    )
  }

  return (
    <div className={className}>
      {label ? (
        <Text size="xs" c="dimmed" mb={2}>
          {label}
        </Text>
      ) : null}
      <Group gap={6} wrap="nowrap" align="center">
        <Code className="mono server-card__url-code" title="Hidden — use copy" style={{ flex: 1, minWidth: 0 }}>
          {masked}
        </Code>
        <Tooltip label={copied ? 'Copied' : 'Copy full URL'}>
          <ActionIcon
            size="sm"
            variant="light"
            color={copied ? 'teal' : 'gray'}
            aria-label="Copy URL"
            onClick={onCopy}
          >
            {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
          </ActionIcon>
        </Tooltip>
      </Group>
    </div>
  )
}
