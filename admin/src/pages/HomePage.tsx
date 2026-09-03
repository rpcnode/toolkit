import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Card,
  Center,
  Divider,
  Group,
  Loader,
  Progress,
  SimpleGrid,
  Stack,
  Text,
  ThemeIcon,
  Tooltip,
} from '@mantine/core'
import {
  IconAlertTriangle,
  IconArrowRight,
  IconCircleCheck,
  IconClock,
  IconPlus,
  IconRefresh,
  IconServer,
  IconTopologyStar3,
} from '@tabler/icons-react'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { api, type PanelSettings, type RegistryNode, type Workload } from '../api'
import { AppChrome, PageHint } from '../components/AppChrome'
import { ChannelLinks } from '../components/ChannelLinks'
import { navigate } from '../lib/router'
import { blockProps } from '../lib/blockId'

type DashboardData = {
  servers: RegistryNode[]
  workloads: Workload[]
  channel: PanelSettings | null
}

function statusColor(status?: string): string {
  switch ((status || '').toLowerCase()) {
    case 'online':
    case 'active':
    case 'synced':
      return 'teal'
    case 'sync':
    case 'installing':
    case 'starting':
    case 'snapshot':
      return 'cyan'
    case 'stale':
    case 'offline':
    case 'error':
    case 'failed':
    case 'remove_error':
      return 'red'
    default:
      return 'gray'
  }
}

function nodeStatus(node: Workload): string {
  return node.lifecycle_label || node.status || 'unknown'
}

function isHealthyNode(node: Workload): boolean {
  return (node.status || '').toLowerCase() === 'active' && node.agent_reachable !== false
}

function isAttentionNode(node: Workload): boolean {
  const status = (node.status || '').toLowerCase()
  return (
    node.agent_reachable === false ||
    Boolean(node.status_error) ||
    ['error', 'failed', 'offline', 'remove_error', 'network_mismatch'].includes(status)
  )
}

function isHealthyServer(server: RegistryNode): boolean {
  return (server.metrics_status || '').toLowerCase() === 'online'
}

function serverName(server: RegistryNode): string {
  return (server.name || server.id).trim()
}

function nodeName(node: Workload): string {
  return node.name || `${node.network} / ${node.env}`
}

function syncProgress(node: Workload): number | undefined {
  if (node.sync_pct != null && Number.isFinite(node.sync_pct) && node.sync_pct >= 0) return node.sync_pct
  if (node.snapshot_progress != null && Number.isFinite(node.snapshot_progress) && node.snapshot_progress >= 0) {
    return node.snapshot_progress
  }
  if (node.height != null && node.network_height != null && node.network_height > 0) {
    return Math.min(100, Math.max(0, (node.height / node.network_height) * 100))
  }
  return undefined
}

export function HomePage() {
  const [data, setData] = useState<DashboardData | null>(null)
  const [loading, setLoading] = useState(true)
  const [refreshing, setRefreshing] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async (manual = false) => {
    if (manual) setRefreshing(true)
    try {
      const [servers, workloads, channel] = await Promise.all([
        api.registryList(),
        api.workloadsList(),
        api.panelSettings().catch(() => null),
      ])
      setData({
        servers: servers.items || [],
        workloads: workloads.items || [],
        channel,
      })
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setLoading(false)
      setRefreshing(false)
    }
  }, [])

  useEffect(() => {
    void load()
    const refresh = window.setInterval(() => void load(), 30_000)
    return () => window.clearInterval(refresh)
  }, [load])

  const overview = useMemo(() => {
    const servers = data?.servers || []
    const nodes = data?.workloads || []
    const healthyServers = servers.filter(isHealthyServer).length
    const healthyNodes = nodes.filter(isHealthyNode).length
    const attention = nodes.filter(isAttentionNode)
    const syncing = nodes.filter((node) => ['sync', 'installing', 'starting', 'snapshot'].includes((node.status || '').toLowerCase()))
    return { servers, nodes, healthyServers, healthyNodes, attention, syncing }
  }, [data])

  if (loading && !data) {
    return (
      <Center mih={240}>
        <Stack align="center" gap="sm">
          <Loader color="teal" />
          <Text c="dimmed">Loading dashboard…</Text>
        </Stack>
      </Center>
    )
  }

  return (
    <AppChrome
      block="dashboard"
      title="Dashboard"
      subtitle={<PageHint>Live overview of servers and blockchain nodes. Refreshes every 30 seconds.</PageHint>}
      right={
        <Tooltip label="Refresh now" withArrow>
          <ActionIcon
            variant="subtle"
            color="teal"
            size="lg"
            loading={refreshing}
            aria-label="Refresh dashboard"
            onClick={() => void load(true)}
          >
            <IconRefresh size={17} />
          </ActionIcon>
        </Tooltip>
      }
    >
      <Stack gap="md" mt="md" {...blockProps('dashboard.content')}>
        {error ? (
          <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Could not refresh dashboard">
            {error}
          </Alert>
        ) : null}

        <SimpleGrid cols={{ base: 1, xs: 2, lg: 4 }} spacing="md" {...blockProps('dashboard.summary')}>
          <Card className="env-card env-card--installed" style={{ cursor: 'pointer' }} onClick={() => navigate({ name: 'servers' })}>
            <Group justify="space-between" mb="sm">
              <ThemeIcon color="teal" variant="light" size="lg"><IconServer size={18} /></ThemeIcon>
              <Badge color={overview.servers.length === overview.healthyServers ? 'teal' : 'red'} variant="light">
                {overview.healthyServers}/{overview.servers.length}
              </Badge>
            </Group>
            <Text fw={700}>Servers</Text>
            <Text size="xs" c="dimmed">online host agents</Text>
          </Card>

          <Card className="env-card env-card--installed" style={{ cursor: 'pointer' }} onClick={() => navigate({ name: 'nodes' })}>
            <Group justify="space-between" mb="sm">
              <ThemeIcon color="cyan" variant="light" size="lg"><IconTopologyStar3 size={18} /></ThemeIcon>
              <Badge color="cyan" variant="light">{overview.nodes.length}</Badge>
            </Group>
            <Text fw={700}>Nodes</Text>
            <Text size="xs" c="dimmed">{overview.healthyNodes} synced and reachable</Text>
          </Card>

          <Card className="env-card env-card--installed" style={{ cursor: 'pointer' }} onClick={() => navigate({ name: 'nodes' })}>
            <Group justify="space-between" mb="sm">
              <ThemeIcon color={overview.syncing.length ? 'cyan' : 'gray'} variant="light" size="lg"><IconClock size={18} /></ThemeIcon>
              <Badge color={overview.syncing.length ? 'cyan' : 'gray'} variant="light">{overview.syncing.length}</Badge>
            </Group>
            <Text fw={700}>In progress</Text>
            <Text size="xs" c="dimmed">installing or syncing nodes</Text>
          </Card>

          <Card className={`env-card env-card--installed${overview.attention.length ? ' env-card--live-fail' : ''}`} style={{ cursor: 'pointer' }} onClick={() => navigate({ name: 'nodes' })}>
            <Group justify="space-between" mb="sm">
              <ThemeIcon color={overview.attention.length ? 'red' : 'teal'} variant="light" size="lg">
                {overview.attention.length ? <IconAlertTriangle size={18} /> : <IconCircleCheck size={18} />}
              </ThemeIcon>
              <Badge color={overview.attention.length ? 'red' : 'teal'} variant="light">{overview.attention.length}</Badge>
            </Group>
            <Text fw={700}>Needs attention</Text>
            <Text size="xs" c="dimmed">{overview.attention.length ? 'nodes with errors or offline agents' : 'all nodes look healthy'}</Text>
          </Card>
        </SimpleGrid>

        {overview.attention.length > 0 ? (
          <Card withBorder {...blockProps('dashboard.attention')}>
            <Group justify="space-between" mb="sm" wrap="wrap">
              <div>
                <Text fw={700}>Needs attention</Text>
                <Text size="xs" c="dimmed">Resolve errors and unreachable agents first.</Text>
              </div>
              <Button variant="subtle" color="red" size="compact-sm" rightSection={<IconArrowRight size={14} />} onClick={() => navigate({ name: 'nodes' })}>
                Open nodes
              </Button>
            </Group>
            <Stack gap={0}>
              {overview.attention.slice(0, 5).map((node, index) => (
                <div key={node.id}>
                  {index > 0 ? <Divider my="xs" /> : null}
                  <Group justify="space-between" wrap="nowrap" gap="sm">
                    <div style={{ minWidth: 0 }}>
                      <Text size="sm" fw={600} truncate>{nodeName(node)}</Text>
                      <Text size="xs" c="dimmed" truncate>{node.status_error || node.lifecycle_detail || `${node.network} / ${node.env}`}</Text>
                    </div>
                    <Badge color={statusColor(node.status)} variant="light" style={{ flexShrink: 0 }}>{nodeStatus(node)}</Badge>
                  </Group>
                </div>
              ))}
            </Stack>
          </Card>
        ) : null}

        <SimpleGrid cols={{ base: 1, lg: 2 }} spacing="md">
          <Card withBorder {...blockProps('dashboard.servers')}>
            <Group justify="space-between" mb="sm">
              <div>
                <Text fw={700}>Server health</Text>
                <Text size="xs" c="dimmed">Latest agent heartbeat</Text>
              </div>
              <Button variant="subtle" size="compact-sm" rightSection={<IconArrowRight size={14} />} onClick={() => navigate({ name: 'servers' })}>All servers</Button>
            </Group>
            {overview.servers.length ? (
              <Stack gap={0}>
                {overview.servers.slice(0, 5).map((server, index) => (
                  <div key={server.id}>
                    {index > 0 ? <Divider my="xs" /> : null}
                    <Group justify="space-between" wrap="nowrap" gap="sm">
                      <div style={{ minWidth: 0 }}>
                        <Text size="sm" fw={600} truncate>{serverName(server)}</Text>
                        <Text size="xs" c="dimmed" truncate>{server.os_pretty || server.agent_version || 'Agent details unavailable'}</Text>
                      </div>
                      <Badge color={statusColor(server.metrics_status)} variant="light" style={{ flexShrink: 0 }}>
                        {server.metrics_status || 'unknown'}
                      </Badge>
                    </Group>
                  </div>
                ))}
              </Stack>
            ) : (
              <EmptyState text="No servers registered yet." action="Add server" onClick={() => navigate({ name: 'install' })} />
            )}
          </Card>

          <Card withBorder {...blockProps('dashboard.nodes')}>
            <Group justify="space-between" mb="sm">
              <div>
                <Text fw={700}>Node activity</Text>
                <Text size="xs" c="dimmed">Installing and syncing workloads</Text>
              </div>
              <Button variant="subtle" size="compact-sm" rightSection={<IconArrowRight size={14} />} onClick={() => navigate({ name: 'nodes' })}>All nodes</Button>
            </Group>
            {overview.syncing.length ? (
              <Stack gap="sm">
                {overview.syncing.slice(0, 4).map((node) => {
                  const progress = syncProgress(node)
                  return (
                    <div key={node.id}>
                      <Group justify="space-between" mb={5} wrap="nowrap">
                        <Text size="sm" fw={600} truncate>{nodeName(node)}</Text>
                        <Badge color="cyan" variant="light" style={{ flexShrink: 0 }}>{nodeStatus(node)}</Badge>
                      </Group>
                      {progress != null ? <Progress value={progress} color="cyan" size="sm" /> : <Text size="xs" c="dimmed">{node.lifecycle_detail || 'Waiting for progress from agent'}</Text>}
                    </div>
                  )
                })}
              </Stack>
            ) : overview.nodes.length ? (
              <EmptyState text="No nodes are currently installing or syncing." action="Open nodes" onClick={() => navigate({ name: 'nodes' })} />
            ) : (
              <EmptyState text="Add a server before creating your first node." action="Add server" onClick={() => navigate({ name: 'install' })} />
            )}
          </Card>
        </SimpleGrid>

        {data?.channel?.links?.length ? (
          <Card withBorder {...blockProps('dashboard.channel')}>
            <ChannelLinks links={data.channel.links} curl={data.channel.curl} scripts={data.channel.scripts} panelScripts={data.channel.panel_scripts} />
          </Card>
        ) : null}

        <Card withBorder {...blockProps('dashboard.add-server')}>
          <Group justify="space-between" wrap="wrap">
            <div>
              <Text fw={600}>Expand your infrastructure</Text>
              <Text size="sm" c="dimmed">Register a host agent, then create nodes for the networks you need.</Text>
            </div>
            <Button color="teal" leftSection={<IconPlus size={16} />} onClick={() => navigate({ name: 'install' })}>Add server</Button>
          </Group>
        </Card>
      </Stack>
    </AppChrome>
  )
}

function EmptyState({ text, action, onClick }: { text: string; action: string; onClick: () => void }) {
  return (
    <Stack gap="xs" py="xs">
      <Text size="sm" c="dimmed">{text}</Text>
      <Button variant="light" size="compact-sm" w="fit-content" onClick={onClick}>{action}</Button>
    </Stack>
  )
}
