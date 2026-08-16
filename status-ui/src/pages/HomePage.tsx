import { Badge, Button, Card, Group, SimpleGrid, Stack, Text, ThemeIcon, Loader, Center, Alert } from '@mantine/core'
import { IconAlertTriangle, IconPlus, IconServer, IconTopologyStar3, IconArrowRight } from '@tabler/icons-react'
import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import { AppChrome, PageHint } from '../components/AppChrome'
import { navigate } from '../lib/router'

export function HomePage() {
  const [serverCount, setServerCount] = useState<number | null>(null)
  const [nodeCount, setNodeCount] = useState<number | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [nodesRes, wlRes] = await Promise.all([
        api.registryList().catch(() => null),
        api.workloadsList().catch(() => null),
      ])

      setServerCount(nodesRes?.count ?? nodesRes?.items?.length ?? 0)
      setNodeCount(wlRes?.count ?? wlRes?.items?.length ?? 0)
      setError(null)
    } catch (e) {
      setError(String((e as Error).message || e))
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  if (loading && serverCount == null) {
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
      title="Dashboard"
      subtitle={<PageHint>Servers host agents; nodes are chain workloads on those servers.</PageHint>}
    >
      <Stack gap="md" mt="md">
        {error && (
          <Alert color="red" icon={<IconAlertTriangle size={16} />} title="Dashboard error">
            {error}
          </Alert>
        )}

        <SimpleGrid cols={{ base: 1, sm: 2 }} spacing="md">
          <Card className="env-card env-card--installed" style={{ cursor: 'pointer' }} onClick={() => navigate({ name: 'servers' })}>
            <Group justify="space-between" mb="sm">
              <Group gap="sm">
                <ThemeIcon color="teal" variant="light" size="lg">
                  <IconServer size={18} />
                </ThemeIcon>
                <div>
                  <Text fw={700}>Servers</Text>
                  <Text size="xs" c="dimmed">
                    Host agents
                  </Text>
                </div>
              </Group>
              <Badge color="teal" variant="light">
                {serverCount ?? 0}
              </Badge>
            </Group>
            <Text size="sm" c="dimmed" mb="sm">
              Register and manage host agents that run on your machines.
            </Text>
            <Group gap={6}>
              <Text size="xs" c="teal.4">
                Open servers
              </Text>
              <IconArrowRight size={12} />
            </Group>
          </Card>

          <Card className="env-card env-card--installed" style={{ cursor: 'pointer' }} onClick={() => navigate({ name: 'nodes' })}>
            <Group justify="space-between" mb="sm">
              <Group gap="sm">
                <ThemeIcon color="cyan" variant="light" size="lg">
                  <IconTopologyStar3 size={18} />
                </ThemeIcon>
                <div>
                  <Text fw={700}>Nodes</Text>
                  <Text size="xs" c="dimmed">
                    Blockchain nodes
                  </Text>
                </div>
              </Group>
              <Badge color="cyan" variant="light">
                {nodeCount ?? 0}
              </Badge>
            </Group>
            <Text size="sm" c="dimmed" mb="sm">
              Chain nodes (network + env) attached to a server agent — expect many.
            </Text>
            <Group gap={6}>
              <Text size="xs" c="cyan.4">
                Open nodes
              </Text>
              <IconArrowRight size={12} />
            </Group>
          </Card>
        </SimpleGrid>

        <Card>
          <Group justify="space-between" wrap="wrap">
            <div>
              <Text fw={600}>Add server</Text>
              <Text size="sm" c="dimmed">
                Install agent → enter IP + secret → check connection → register. Network/env when
                adding a node.
              </Text>
            </div>
            <Button
              variant="light"
              color="teal"
              leftSection={<IconPlus size={16} />}
              onClick={() => navigate({ name: 'install' })}
            >
              Add server
            </Button>
          </Group>
        </Card>
      </Stack>
    </AppChrome>
  )
}
