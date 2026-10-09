import { Anchor, Button, Group, Modal, Stack, Text, Loader, Alert, Code } from '@mantine/core'
import { IconBook2, IconExternalLink } from '@tabler/icons-react'
import { useEffect, useState } from 'react'
import { MarkdownDoc } from '../lib/markdown'
import { api, type DevApiCatalog } from '../api'
import { blockProps } from '../lib/blockId'

type Props = {
  opened: boolean
  onClose: () => void
  baseUrl?: string
}

export function ApiDocsModal({ opened, onClose, baseUrl }: Props) {
  const [md, setMd] = useState<string | null>(null)
  const [catalog, setCatalog] = useState<DevApiCatalog | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    if (!opened) return
    let cancelled = false
    setErr(null)
    void (async () => {
      try {
        const [doc, cat] = await Promise.all([
          api.developerDocsMd().catch(() => null),
          api.devCatalog().catch(() => null),
        ])
        if (cancelled) return
        setMd(doc)
        setCatalog(cat)
        if (!doc && !cat) setErr('Could not load docs')
      } catch (e) {
        if (!cancelled) setErr(String((e as Error).message || e))
      }
    })()
    return () => {
      cancelled = true
    }
  }, [opened])

  const base = (baseUrl || window.location.origin).replace(/\/$/, '')

  return (
    <Modal opened={opened} onClose={onClose} title="Developer API" size="xl" centered padding="lg" {...blockProps('modal.api-docs')}>
      <Stack gap="md">
        <Text size="sm" c="dimmed">
          Stable JSON API for integrations. Auth: panel basic auth <strong>or</strong> Bearer /{' '}
          <Code>X-Api-Token</Code>. Catalog:{' '}
          <Anchor href={`${base}/api/v1`} target="_blank" rel="noreferrer">
            GET /api/v1
          </Anchor>
        </Text>

        {catalog?.endpoints && (
          <Alert color="cyan" variant="light" title="Live catalog">
            <Stack gap={4}>
              {catalog.endpoints.map((e) => (
                <Text key={e.path} size="sm" className="mono">
                  {e.method} {e.path}
                  {e.desc ? ` — ${e.desc}` : ''}
                </Text>
              ))}
            </Stack>
            <Group mt="sm" gap="sm">
              <Button
                component="a"
                href={`${base}/api/v1`}
                target="_blank"
                size="xs"
                variant="light"
                rightSection={<IconExternalLink size={14} />}
              >
                Open GET /api/v1
              </Button>
              <Text size="xs" c="dimmed">
                token set: {catalog.auth?.token_set ? 'yes' : 'no (basic only)'}
              </Text>
            </Group>
          </Alert>
        )}

        {err && (
          <Alert color="red" variant="light">
            {err}
          </Alert>
        )}

        {!md && !err && <Loader color="teal" size="sm" />}

        {md && <MarkdownDoc source={md} />}

        <Text size="xs" c="dimmed">
          Source of truth: <Code>docs/developer-api.md</Code> (served as{' '}
          <Code>/status/docs/developer-api.md</Code>).
        </Text>
      </Stack>
    </Modal>
  )
}

export function ApiDocsButton({ onClick }: { onClick: () => void }) {
  return (
    <Button
      leftSection={<IconBook2 size={12} />}
      variant="light"
      color="cyan"
      size="compact-xs"
      onClick={onClick}
    >
      API
    </Button>
  )
}
