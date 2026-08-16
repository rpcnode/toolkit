import { Card, Group, Stack, Text, Title } from '@mantine/core'
import { AppChrome, PageHint } from '../components/AppChrome'
import { ThemeToggle } from '../components/ThemeToggle'

export function SettingsPage() {
  return (
    <AppChrome
      title="Settings"
      subtitle={<PageHint>Panel preferences. Theme is also available in the header.</PageHint>}
    >
      <Stack gap="md" mt="md">
        <Card>
          <Group justify="space-between" align="center" wrap="wrap">
            <div>
              <Title order={4}>Appearance</Title>
              <Text size="sm" c="dimmed">
                Light, dark, or follow system (auto).
              </Text>
            </div>
            <ThemeToggle />
          </Group>
        </Card>

        <Card>
          <Stack gap={4}>
            <Title order={4}>Hierarchy</Title>
            <Text size="sm" c="dimmed">
              <Text span fw={600}>
                Server / Agent
              </Text>{' '}
              — host process that runs on a machine.
            </Text>
            <Text size="sm" c="dimmed">
              <Text span fw={600}>
                Node
              </Text>{' '}
              — blockchain workload (network + env) attached to a server. Many nodes per agent.
            </Text>
          </Stack>
        </Card>
      </Stack>
    </AppChrome>
  )
}
