import { Alert, Anchor, Button, Card, Group, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core'
import { useState } from 'react'
import { api } from '../api'
import { navigate } from '../lib/router'
import { BrandLogo } from '../components/BrandLogo'
import { ThemeToggle } from '../components/ThemeToggle'

const RPCNODE = 'https://rpcnode.dev'

export function LoginPage() {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.login(username.trim(), password)
      navigate({ name: 'dashboard' })
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-shell">
      <Group justify="flex-end" style={{ position: 'fixed', top: 12, right: 12 }}>
        <ThemeToggle />
      </Group>
      <Card className="auth-card" padding="xl" radius="md">
        <Stack gap="md">
          <div>
            <Text className="brand-mark" mb={4}>
              <BrandLogo size={28} />
              RpcNode
            </Text>
            <Title order={2}>Sign in</Title>
            <Text c="dimmed" size="sm" mt={4}>
              Local control panel — agent API key stays separate for machines.
            </Text>
          </div>

          {error && (
            <Alert color="red" title="Sign in failed">
              {error}
            </Alert>
          )}

          <form onSubmit={(e) => void submit(e)}>
            <Stack gap="sm">
              <TextInput
                label="Username or email"
                value={username}
                onChange={(e) => setUsername(e.currentTarget.value)}
                autoComplete="username"
                required
              />
              <PasswordInput
                label="Password"
                value={password}
                onChange={(e) => setPassword(e.currentTarget.value)}
                autoComplete="current-password"
                required
              />
              <Button type="submit" color="teal" loading={busy} fullWidth mt="xs">
                Sign in
              </Button>
            </Stack>
          </form>

          <Text size="xs" c="dimmed">
            First start?{' '}
            <Anchor component="button" type="button" onClick={() => navigate({ name: 'setupPassword' })}>
              Create admin password
            </Anchor>
            {' · '}
            <Anchor href={RPCNODE} target="_blank" rel="noopener noreferrer">
              rpcnode.dev
            </Anchor>
          </Text>
        </Stack>
      </Card>
    </div>
  )
}
