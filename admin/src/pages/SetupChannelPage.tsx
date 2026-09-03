import { Alert, Button, Card, Group, Stack, Text, Title } from '@mantine/core'
import { useEffect, useState } from 'react'
import { api } from '../api'
import { navigate } from '../lib/router'
import { BrandLogo } from '../components/BrandLogo'
import { ThemeToggle } from '../components/ThemeToggle'
import { ChannelOriginFields, ORIGIN_LOCAL } from '../components/ChannelOriginFields'
import { ChannelLinks } from '../components/ChannelLinks'
import type { PanelSettings } from '../api'
import { blockProps } from '../lib/blockId'

export function SetupChannelPage() {
  const [origin, setOrigin] = useState(ORIGIN_LOCAL)
  const [settings, setSettings] = useState<PanelSettings | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    void api
      .panelSettings()
      .then((s) => {
        setSettings(s)
        setOrigin(s.install_origin || s.presets?.local || ORIGIN_LOCAL)
      })
      .catch(() => {
        /* ignore */
      })
  }, [])

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!origin.trim()) {
      setError('Pick an install origin')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.savePanelSettings({ install_origin: origin.trim() })
      navigate({ name: 'dashboard' })
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="auth-shell" {...blockProps('setup.channel')}>
      <Group justify="flex-end" style={{ position: 'fixed', top: 12, right: 12 }}>
        <ThemeToggle />
      </Group>
      <Card className="auth-card" padding="xl" radius="md" {...blockProps('setup.channel.form')}>
        <Stack gap="md">
          <div>
            <Text className="brand-mark" mb={4}>
              <BrandLogo size={28} />
              RpcNode
            </Text>
            <Title order={2}>Install origin</Title>
            <Text c="dimmed" size="sm" mt={4}>
              Stored in toolkit.db. Agents pull the jar and clients from this origin.
            </Text>
          </div>

          {error && (
            <Alert color="red" title="Save failed">
              {error}
            </Alert>
          )}

          <form onSubmit={(e) => void submit(e)}>
            <Stack gap="sm">
              <ChannelOriginFields origin={origin} onChange={setOrigin} presets={settings?.presets} />
              <ChannelLinks
                links={settings?.links}
                curl={settings?.curl}
                scripts={settings?.scripts}
                panelScripts={settings?.panel_scripts}
              />
              <Button type="submit" color="teal" loading={busy} fullWidth mt="xs">
                Save & continue
              </Button>
            </Stack>
          </form>
        </Stack>
      </Card>
    </div>
  )
}
