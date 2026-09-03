import {
  Alert,
  Badge,
  Button,
  Card,
  Checkbox,
  Code,
  Group,
  Modal,
  NumberInput,
  PasswordInput,
  Radio,
  Stack,
  Stepper,
  Switch,
  Text,
  Title,
} from '@mantine/core'
import { notifications } from '@mantine/notifications'
import {
  IconAlertTriangle,
  IconBell,
  IconBrandTelegram,
  IconCheck,
  IconRefresh,
} from '@tabler/icons-react'
import { useCallback, useEffect, useState } from 'react'
import { api, type NotifySettings, type TelegramBot, type TelegramChat } from '../api'
import { AppChrome, PageHint } from '../components/AppChrome'
import { blockProps } from '../lib/blockId'

export function NotificationsPage() {
  const [settings, setSettings] = useState<NotifySettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [telegramModalOpen, setTelegramModalOpen] = useState(false)
  const [enabled, setEnabled] = useState(false)
  const [subs, setSubs] = useState<Record<string, boolean>>({})
  const [diskPct, setDiskPct] = useState<number | string>(90)
  const [cpuPct, setCpuPct] = useState<number | string>(90)
  const [latencyMs, setLatencyMs] = useState<number | string>(2000)
  const [errorRatePct, setErrorRatePct] = useState<number | string>(10)
  const [rpcRps, setRpcRps] = useState<number | string>(1000)
  const [nodeDownHoldSec, setNodeDownHoldSec] = useState<number | string>(45)
  const [nodeUpHoldSec, setNodeUpHoldSec] = useState<number | string>(20)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const st = await api.notifySettings()
      setSettings(st)
      setEnabled(!!st.enabled)
      setSubs({ ...(st.subscriptions || {}) })
      setDiskPct(st.thresholds?.disk_used_pct ?? 90)
      setCpuPct(st.thresholds?.cpu_high_pct ?? 90)
      setLatencyMs(st.thresholds?.rpc_latency_p95_ms ?? 2000)
      setErrorRatePct(st.thresholds?.rpc_error_rate_pct ?? 10)
      setRpcRps(st.thresholds?.rpc_rps ?? 1000)
      setNodeDownHoldSec(st.thresholds?.node_down_hold_sec ?? 45)
      setNodeUpHoldSec(st.thresholds?.node_up_hold_sec ?? 20)
    } catch (e) {
      notifications.show({
        color: 'red',
        message: String((e as Error).message || e),
      })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  async function save(enabledValue = enabled) {
    setSaving(true)
    try {
      const body: Parameters<typeof api.notifySaveSettings>[0] = {
        enabled: enabledValue,
        subscriptions: subs,
        thresholds: {
          disk_used_pct: Number(diskPct) || 90,
          cpu_high_pct: Number(cpuPct) || 90,
          rpc_latency_p95_ms: Number(latencyMs) || 2000,
          rpc_error_rate_pct: Number(errorRatePct) || 10,
          rpc_rps: Number(rpcRps) || 1000,
          node_down_hold_sec: Number(nodeDownHoldSec) || 45,
          node_up_hold_sec: Number(nodeUpHoldSec) || 20,
        },
      }
      const st = await api.notifySaveSettings(body)
      setSettings(st)
      setEnabled(!!st.enabled)
      setSubs({ ...(st.subscriptions || {}) })
      setDiskPct(st.thresholds?.disk_used_pct ?? 90)
      setCpuPct(st.thresholds?.cpu_high_pct ?? 90)
      setLatencyMs(st.thresholds?.rpc_latency_p95_ms ?? 2000)
      setErrorRatePct(st.thresholds?.rpc_error_rate_pct ?? 10)
      setRpcRps(st.thresholds?.rpc_rps ?? 1000)
      setNodeDownHoldSec(st.thresholds?.node_down_hold_sec ?? 45)
      setNodeUpHoldSec(st.thresholds?.node_up_hold_sec ?? 20)
      notifications.show({ color: 'teal', message: 'Notification settings saved' })
    } catch (e) {
      notifications.show({
        color: 'red',
        message: String((e as Error).message || e),
      })
    } finally {
      setSaving(false)
    }
  }

  const catalog = settings?.subscription_catalog || []

  return (
    <AppChrome
      block="notifications"
      title="Notifications"
      subtitle={
        <PageHint>
          Telegram alerts for client updates, install lifecycle, node down/up, agent updates, disk.
        </PageHint>
      }
    >
      <Stack gap="md" mt="md" {...blockProps('notifications.content')}>
        <Card {...blockProps('notifications.telegram')}>
          <Stack gap="md">
            <Group justify="space-between" align="flex-start" wrap="wrap">
              <Group gap="xs">
                {settings?.verified ? (
                  <Badge color="teal" leftSection={<IconCheck size={12} />}>
                    Verified
                  </Badge>
                ) : (
                  <Badge color="yellow">Not verified</Badge>
                )}
                {settings?.has_token ? (
                  <Badge color="gray" variant="light">
                    Token {settings.token_masked || '••••'}
                  </Badge>
                ) : null}
              </Group>
            </Group>

            {settings?.has_token && settings.token_decrypt_ok === false ? (
              <Alert color="red" icon={<IconAlertTriangle size={16} />}>
                Cannot decrypt stored token (missing/rotated <Code>panel.notify.key</Code>). Re-enter
                the bot token and verify again.
              </Alert>
            ) : null}

            {settings?.last_error ? (
              <Alert color="orange" icon={<IconAlertTriangle size={16} />}>
                {settings.last_error}
              </Alert>
            ) : null}

            <Switch
              label="Enable alerts"
              description="Requires a connected Telegram chat."
              checked={enabled}
              disabled={loading || !settings?.verified}
              onChange={(e) => {
                const nextEnabled = e.currentTarget.checked
                setEnabled(nextEnabled)
                void save(nextEnabled)
              }}
            />

            <Group>
              <Button
                leftSection={<IconBrandTelegram size={16} />}
                variant={settings?.verified ? 'light' : 'filled'}
                color="blue"
                disabled={loading}
                onClick={() => setTelegramModalOpen(true)}
              >
                {settings?.verified ? 'Change Telegram chat' : 'Connect Telegram'}
              </Button>
            </Group>

            {settings?.key_source ? (
              <Text size="xs" c="dimmed">
                Encryption key source: <Code>{settings.key_source}</Code>
                {settings.key_path ? (
                  <>
                    {' '}
                    · <Code>{settings.key_path}</Code>
                  </>
                ) : null}
                . Backup DB <strong>and</strong> key (or set <Code>RPCNODE_NOTIFY_KEY</Code>).
              </Text>
            ) : null}
          </Stack>
        </Card>

        <Card {...blockProps('notifications.subscriptions')}>
          <Stack gap="sm">
            <Group gap="xs">
              <IconBell size={18} stroke={1.5} />
              <Title order={4}>Subscriptions</Title>
            </Group>
            <Text size="sm" c="dimmed">
              Choose which events the panel sends to Telegram after the channel is verified.
            </Text>
            {catalog.map((item) => (
              <Checkbox
                key={item.key}
                label={item.label}
                description={item.description}
                checked={!!subs[item.key]}
                disabled={loading}
                onChange={(e) =>
                  setSubs((prev) => ({ ...prev, [item.key]: e.currentTarget.checked }))
                }
              />
            ))}
            <Title order={5} mt="sm">
              Alert thresholds
            </Title>
            <Text size="sm" c="dimmed">
              Host: disk/CPU default <Code>90%</Code>. Fullnode Go RPC (public proxy): RPS default{' '}
              <Code>1000</Code>/s, p95 <Code>2000 ms</Code>, errors <Code>10%</Code>. Node down/up
              require a continuous hold (default <Code>45s</Code> / <Code>20s</Code>) so a single
              poll timeout does not flap Telegram.
            </Text>
            <Text size="sm" fw={600} mt={4}>
              Host
            </Text>
            <Group grow align="flex-start">
              <NumberInput
                label="Disk used (%)"
                description="Disk low when used ≥ this"
                min={1}
                max={100}
                step={1}
                value={diskPct}
                onChange={setDiskPct}
                disabled={loading}
              />
              <NumberInput
                label="CPU (%)"
                description="CPU high when cpu_pct ≥ this (not load)"
                min={1}
                max={100}
                step={1}
                value={cpuPct}
                onChange={setCpuPct}
                disabled={loading}
              />
            </Group>
            <Text size="sm" fw={600} mt={4}>
              Node reachability
            </Text>
            <Group grow align="flex-start">
              <NumberInput
                label="Down hold (sec)"
                description="Continuous unreachable before node.down"
                min={5}
                max={3600}
                step={5}
                value={nodeDownHoldSec}
                onChange={setNodeDownHoldSec}
                disabled={loading}
              />
              <NumberInput
                label="Up hold (sec)"
                description="Continuous healthy before node.up"
                min={5}
                max={3600}
                step={5}
                value={nodeUpHoldSec}
                onChange={setNodeUpHoldSec}
                disabled={loading}
              />
            </Group>
            <Group gap="xs" align="center" mt={4}>
              <Text size="sm" fw={600}>
                Fullnode Go RPC
              </Text>
              <Badge size="sm" variant="light" color="teal">
                public proxy
              </Badge>
            </Group>
            <Group grow align="flex-start">
              <NumberInput
                label="Requests / sec (RPS)"
                description="rps_1m on public Go proxy ≥ this"
                min={1}
                max={1_000_000}
                step={100}
                value={rpcRps}
                onChange={setRpcRps}
                disabled={loading}
              />
              <NumberInput
                label="Latency p95 (ms)"
                description="Proxy p95 ≥ this"
                min={50}
                max={120000}
                step={100}
                value={latencyMs}
                onChange={setLatencyMs}
                disabled={loading}
              />
              <NumberInput
                label="Error rate (%)"
                description="5xx+upstream rate ≥ this"
                min={0.1}
                max={100}
                step={0.5}
                decimalScale={1}
                value={errorRatePct}
                onChange={setErrorRatePct}
                disabled={loading}
              />
            </Group>
            <Group>
              <Button
                variant="light"
                loading={saving}
                disabled={loading}
                onClick={() => void save()}
              >
                Save subscriptions
              </Button>
            </Group>
          </Stack>
        </Card>
      </Stack>
      <TelegramConnectModal
        opened={telegramModalOpen}
        onClose={() => setTelegramModalOpen(false)}
        onConnected={() => {
          setTelegramModalOpen(false)
          void load()
        }}
      />
    </AppChrome>
  )
}

function TelegramConnectModal({
  opened,
  onClose,
  onConnected,
}: {
  opened: boolean
  onClose: () => void
  onConnected: () => void
}) {
  const [step, setStep] = useState(0)
  const [token, setToken] = useState('')
  const [bot, setBot] = useState<TelegramBot | null>(null)
  const [chats, setChats] = useState<TelegramChat[]>([])
  const [selectedChatId, setSelectedChatId] = useState('')
  const [savingToken, setSavingToken] = useState(false)
  const [findingChats, setFindingChats] = useState(false)
  const [selectingChat, setSelectingChat] = useState(false)

  useEffect(() => {
    if (!opened) return
    setStep(0)
    setToken('')
    setBot(null)
    setChats([])
    setSelectedChatId('')
  }, [opened])

  async function saveToken() {
    if (!token.trim()) {
      notifications.show({ color: 'yellow', message: 'Enter the token from BotFather' })
      return
    }
    setSavingToken(true)
    try {
      const configuredBot = await api.notifyConfigureTelegramBot(token.trim())
      setBot(configuredBot)
      setToken('')
      setStep(1)
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setSavingToken(false)
    }
  }

  async function findChats() {
    setFindingChats(true)
    try {
      const result = await api.notifyDiscoverTelegramChats()
      setChats(result.chats || [])
      setStep(2)
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setFindingChats(false)
    }
  }

  async function selectChat() {
    const chatId = Number(selectedChatId)
    if (!Number.isSafeInteger(chatId)) return
    setSelectingChat(true)
    try {
      await api.notifySelectTelegramChat(chatId)
      notifications.show({ color: 'teal', message: 'Telegram chat connected' })
      onConnected()
    } catch (e) {
      notifications.show({ color: 'red', message: String((e as Error).message || e) })
    } finally {
      setSelectingChat(false)
    }
  }

  return (
    <Modal
      {...blockProps('modal.telegram-connect')}
      opened={opened}
      onClose={onClose}
      title="Connect Telegram"
      size="lg"
      centered
      closeOnClickOutside={false}
    >
      <Stack gap="md">
        <Stepper active={step} size="sm">
          <Stepper.Step label="Bot token" />
          <Stepper.Step label="Send a message" />
          <Stepper.Step label="Choose chat" />
        </Stepper>

        {step === 0 && (
          <>
            <Text size="sm">Paste the token created for the bot in @BotFather.</Text>
            <PasswordInput
              label="Bot token"
              placeholder="123456:ABC..."
              value={token}
              onChange={(event) => setToken(event.currentTarget.value)}
              autoComplete="off"
            />
            <Group justify="flex-end">
              <Button leftSection={<IconCheck size={16} />} loading={savingToken} onClick={() => void saveToken()}>
                Verify bot token
              </Button>
            </Group>
          </>
        )}

        {step === 1 && (
          <>
            <Alert color="blue" icon={<IconBrandTelegram size={16} />}>
              Add {bot?.username ? <Code>@{bot.username}</Code> : 'the bot'} to the target group or channel as an
              administrator, then send a new text message in that chat.
            </Alert>
            <Text size="sm" c="dimmed">
              Telegram only provides chats where the bot has received an update. After sending the message, search
              for chats below.
            </Text>
            <Group justify="space-between">
              <Button variant="default" onClick={() => setStep(0)}>
                Back
              </Button>
              <Button leftSection={<IconRefresh size={16} />} loading={findingChats} onClick={() => void findChats()}>
                Find chats
              </Button>
            </Group>
          </>
        )}

        {step === 2 && (
          <>
            {chats.length === 0 ? (
              <Alert color="yellow">
                No chats found. Add the bot as an administrator, send a new text message in the chat, then search
                again.
              </Alert>
            ) : (
              <Radio.Group value={selectedChatId} onChange={setSelectedChatId} label="Chats where the bot received a message">
                <Stack gap="xs" mt="xs">
                  {chats.map((chat) => (
                    <Radio
                      key={chat.id}
                      value={String(chat.id)}
                      label={`${chat.title} (${chat.type})${chat.username ? ` · @${chat.username}` : ''}`}
                    />
                  ))}
                </Stack>
              </Radio.Group>
            )}
            <Group justify="space-between">
              <Button variant="default" onClick={() => setStep(1)}>
                Back
              </Button>
              <Group>
                <Button variant="light" leftSection={<IconRefresh size={16} />} loading={findingChats} onClick={() => void findChats()}>
                  Search again
                </Button>
                <Button loading={selectingChat} disabled={!selectedChatId} onClick={() => void selectChat()}>
                  Connect chat
                </Button>
              </Group>
            </Group>
          </>
        )}
      </Stack>
    </Modal>
  )
}
