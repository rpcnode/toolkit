import { Alert, Anchor, Button, Card, Group, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core'
import { useState } from 'react'
import { api } from '../api'
import { navigate } from '../lib/router'
import { BrandLogo } from '../components/BrandLogo'
import { ThemeToggle } from '../components/ThemeToggle'

export function SetupPasswordPage() {
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (password.length < 8) {
      setError('Password must be at least 8 characters')
      return
    }
    if (password !== confirm) {
      setError('Passwords do not match')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.setupPassword(username.trim() || 'admin', password)
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
            <Title order={2}>Create admin</Title>
            <Text c="dimmed" size="sm" mt={4}>
              First start — set the panel password before managing nodes. This is for humans; machine
              agents use <code>AGENT_API_TOKEN</code>.
            </Text>
          </div>

          {error && (
            <Alert color="red" title="Setup failed">
              {error}
            </Alert>
          )}

          <form onSubmit={(e) => void submit(e)}>
            <Stack gap="sm">
              <TextInput
                label="Admin username"
                value={username}
                onChange={(e) => setUsername(e.currentTarget.value)}
                autoComplete="username"
                required
              />
              <PasswordInput
                label="Password"
                description="At least 8 characters"
                value={password}
                onChange={(e) => setPassword(e.currentTarget.value)}
                autoComplete="new-password"
                required
              />
              <PasswordInput
                label="Confirm password"
                value={confirm}
                onChange={(e) => setConfirm(e.currentTarget.value)}
                autoComplete="new-password"
                required
              />
              <Button type="submit" color="teal" loading={busy} fullWidth mt="xs">
                Create admin & continue
              </Button>
            </Stack>
          </form>

          <Text size="xs" c="dimmed">
            Already configured?{' '}
            <Anchor component="button" type="button" onClick={() => navigate({ name: 'login' })}>
              Sign in
            </Anchor>
          </Text>
        </Stack>
      </Card>
    </div>
  )
}
