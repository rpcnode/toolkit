import { ActionIcon, Group, Text, TextInput, Tooltip } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { IconCheck, IconCopy } from '@tabler/icons-react'
import { useEffect, useRef, useState } from 'react'
import { copyText } from '../lib/copyText'

/** Phrase the user must type to enable destructive Remove (e.g. stellar/testnet). Empty env → network only. */
export function removeConfirmPhrase(network?: string | null, env?: string | null): string {
  const n = (network || 'node').toLowerCase().trim() || 'node'
  const e = (env ?? '').toLowerCase().trim()
  if (!e) return n
  return `${n}/${e}`
}

export function removePhraseMatches(typed: string, phrase: string): boolean {
  return typed.trim().toLowerCase() === phrase.trim().toLowerCase()
}

export function RemoveConfirmInput({
  phrase,
  value,
  onChange,
  disabled,
  description = 'Required before Remove — prevents accidental deletes.',
}: {
  phrase: string
  value: string
  onChange: (v: string) => void
  disabled?: boolean
  description?: string
}) {
  const [copied, setCopied] = useState(false)
  const copiedTimer = useRef<ReturnType<typeof setTimeout> | null>(null)

  useEffect(() => {
    return () => {
      if (copiedTimer.current) clearTimeout(copiedTimer.current)
    }
  }, [])

  function copyPhrase() {
    const text = (phrase || '').trim()
    if (!text) return
    void copyText(text)
      .then(() => {
        setCopied(true)
        if (copiedTimer.current) clearTimeout(copiedTimer.current)
        copiedTimer.current = setTimeout(() => setCopied(false), 1500)
        notifications.show({ color: 'teal', message: 'Copied', autoClose: 1500 })
      })
      .catch(() => {
        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
      })
  }

  return (
    <TextInput
      label={
        <Group gap={6} wrap="nowrap" align="center">
          <Text span size="sm" fw={500}>
            Type{' '}
            <Text span fw={700} className="mono">
              {phrase}
            </Text>{' '}
            to confirm
          </Text>
          <Tooltip label={copied ? 'Copied' : 'Copy phrase'}>
            <ActionIcon
              size="sm"
              variant="subtle"
              color={copied ? 'teal' : 'gray'}
              aria-label="Copy confirm phrase"
              onClick={(e) => {
                e.preventDefault()
                e.stopPropagation()
                copyPhrase()
              }}
            >
              {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
            </ActionIcon>
          </Tooltip>
        </Group>
      }
      description={description}
      placeholder={phrase}
      value={value}
      onChange={(e) => onChange(e.currentTarget.value)}
      disabled={disabled}
      autoComplete="off"
      autoFocus
      className="mono"
    />
  )
}
