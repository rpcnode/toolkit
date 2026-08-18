import { Alert, Button, Code, Group, Loader, Modal, Stack, Text } from '@mantine/core'
import { IconCopy, IconHeartHandshake, IconRefresh } from '@tabler/icons-react'
import { notifications } from '@mantine/notifications'
import { useEffect, useState } from 'react'
import { api, type DonatePayload, type DonateWallet } from '../api'
import { copyText } from '../lib/copyText'

type Props = {
  opened: boolean
  onClose: () => void
}

export function DonateModal({ opened, onClose }: Props) {
  const [loading, setLoading] = useState(false)
  const [doc, setDoc] = useState<DonatePayload | null>(null)
  const [error, setError] = useState('')

  async function load(refresh = false) {
    setLoading(true)
    setError('')
    try {
      const res = await api.donate(refresh ? { refresh: true } : undefined)
      if (!res?.ok || !res.wallets?.length) {
        setDoc(null)
        setError(res?.message || 'Donate wallets unavailable')
        return
      }
      setDoc(res)
    } catch (e) {
      setDoc(null)
      setError(e instanceof Error ? e.message : 'Failed to load donate wallets')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    if (opened) void load()
  }, [opened])

  function copy(w: DonateWallet) {
    void copyText(w.address)
      .then(() => {
        notifications.show({
          color: 'teal',
          message: `${w.label || w.network} address copied`,
        })
      })
      .catch(() => {
        notifications.show({ color: 'red', message: 'Copy failed', autoClose: 2000 })
      })
  }

  const title = doc?.title || 'Support RpcNode'
  const blurb =
    doc?.blurb ||
    'If this toolkit saves you time, a small tip helps us keep building. We will be very grateful.'
  const footer =
    doc?.footer ||
    'Double-check the network in your wallet before sending. Tips are voluntary and never required to use the panel or agents.'

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      title={
        <Group gap="xs">
          <IconHeartHandshake size={20} stroke={1.5} aria-hidden />
          <Text fw={600}>{title}</Text>
        </Group>
      }
      size="md"
      centered
    >
      <Stack gap="md">
        <Group justify="space-between" align="flex-start" wrap="nowrap">
          <Text size="sm" style={{ flex: 1 }}>
            {blurb}
          </Text>
          <Button
            size="compact-xs"
            variant="subtle"
            color="gray"
            leftSection={<IconRefresh size={12} />}
            loading={loading}
            onClick={() => void load(true)}
          >
            Refresh
          </Button>
        </Group>

        {loading && !doc ? (
          <Group justify="center" py="md">
            <Loader size="sm" />
          </Group>
        ) : null}

        {error && !doc ? (
          <Alert color="yellow" variant="light" title="Could not load wallets">
            <Text size="sm">{error}</Text>
            <Text size="xs" c="dimmed" mt={6}>
              Source: https://toolkit.rpcnode.dev/install/donate.json
            </Text>
          </Alert>
        ) : null}

        {doc?.wallets?.map((w) => (
          <Alert
            key={`${w.network}:${w.address}`}
            color="teal"
            variant="light"
            title={w.label || w.network}
          >
            <Stack gap="xs">
              {w.note ? (
                <Text size="sm" c="dimmed">
                  {w.note}
                </Text>
              ) : (
                <Text size="sm" c="dimmed">
                  Network: {w.network}
                </Text>
              )}
              <Code block style={{ wordBreak: 'break-all' }}>
                {w.address}
              </Code>
              <Group justify="flex-end">
                <Button
                  size="sm"
                  color="teal"
                  leftSection={<IconCopy size={14} />}
                  onClick={() => copy(w)}
                >
                  Copy address
                </Button>
              </Group>
            </Stack>
          </Alert>
        ))}

        <Text size="xs" c="dimmed">
          {footer}
        </Text>
      </Stack>
    </Modal>
  )
}
