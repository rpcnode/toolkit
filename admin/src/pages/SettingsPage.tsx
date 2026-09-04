import { Alert, Anchor, Badge, Button, Card, Code, Group, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core'
import { notifications } from '@mantine/notifications'
import { useEffect, useState } from 'react'
import { api, type PanelSettings } from '../api'
import { AppChrome, PageHint } from '../components/AppChrome'
import { ThemeToggle } from '../components/ThemeToggle'
import { ChannelOriginFields } from '../components/ChannelOriginFields'
import { ChannelLinks } from '../components/ChannelLinks'
import { blockProps } from '../lib/blockId'
import {
  advertisedOrigin,
  CDN_LISTEN_PORT,
  isLoopbackHost,
  originHost,
  SERVER_LISTEN_PORT,
  suggestedAdvertisedHost,
} from '../lib/advertisedOrigin'

export function SettingsPage() {
  const [origin, setOrigin] = useState('')
  const [snapshotCdnOrigin, setSnapshotCdnOrigin] = useState('')
  const [saved, setSaved] = useState<PanelSettings | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [savingSnapshotCdn, setSavingSnapshotCdn] = useState(false)
  const [savingToken, setSavingToken] = useState(false)
  const [githubToken, setGithubToken] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [snapshotCdnError, setSnapshotCdnError] = useState<string | null>(null)
  const [tokenError, setTokenError] = useState<string | null>(null)

  async function load() {
    setLoading(true)
    setError(null)
    try {
      const s = await api.panelSettings()
      setSaved(s)
      const host = suggestedAdvertisedHost(s.install_origin, s.presets?.panel)
      if (s.install_origin) setOrigin(s.install_origin)
      else setOrigin(host ? advertisedOrigin(host, SERVER_LISTEN_PORT) : '')
      setSnapshotCdnOrigin(s.snapshot_cdn_origin || s.snapshot_cdn?.origin || '')
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  async function save() {
    setSaving(true)
    setError(null)
    try {
      const s = await api.savePanelSettings({ install_origin: origin.trim() })
      setSaved(s)
      notifications.show({ color: 'teal', message: 'Saved' })
    } catch (err) {
      setError(String((err as Error).message || err))
    } finally {
      setSaving(false)
    }
  }

  async function saveSnapshotCdn() {
    setSavingSnapshotCdn(true)
    setSnapshotCdnError(null)
    try {
      const s = await api.savePanelSettings({ snapshot_cdn_origin: snapshotCdnOrigin.trim() })
      setSaved(s)
      setSnapshotCdnOrigin(s.snapshot_cdn_origin || '')
      notifications.show({ color: 'teal', message: 'Saved' })
    } catch (err) {
      setSnapshotCdnError(String((err as Error).message || err))
    } finally {
      setSavingSnapshotCdn(false)
    }
  }

  async function saveGithubToken() {
    const tok = githubToken.trim()
    if (!tok) {
      setTokenError('Paste a GitHub personal access token')
      return
    }
    setSavingToken(true)
    setTokenError(null)
    try {
      const s = await api.savePanelSettings({ github_token: tok })
      setSaved(s)
      setGithubToken('')
      if (s.warning) {
        notifications.show({ color: 'orange', title: 'Token saved', message: s.warning })
      } else {
        notifications.show({ color: 'teal', message: 'Saved' })
      }
    } catch (err) {
      setTokenError(String((err as Error).message || err))
    } finally {
      setSavingToken(false)
    }
  }

  async function clearGithubToken() {
    setSavingToken(true)
    setTokenError(null)
    try {
      const s = await api.savePanelSettings({ clear_github_token: true })
      setSaved(s)
      setGithubToken('')
      notifications.show({ color: 'teal', title: 'Cleared', message: 'GitHub token removed' })
    } catch (err) {
      setTokenError(String((err as Error).message || err))
    } finally {
      setSavingToken(false)
    }
  }

  const suggestHost = suggestedAdvertisedHost(origin, saved?.presets?.panel)

  return (
    <AppChrome
      block="settings"
      title="Settings"
      subtitle={<PageHint>Panel preferences and the install channel used by agent builds.</PageHint>}
    >
      <Stack gap="md" mt="md" {...blockProps('settings.content')}>
        {saved?.install || saved?.panel_version ? (
          <Card {...blockProps('settings.install')}>
            <Stack gap={4}>
              <Title order={4}>Install</Title>
              <Text size="sm" c="dimmed">
                Marker <Code>database/panel.install</Code>
                {saved.install?.version ? (
                  <>
                    {' '}
                    — {saved.install.version}
                    {saved.install.installed_at ? ` · ${saved.install.installed_at}` : ''}
                  </>
                ) : null}
                {saved.panel_version && saved.install?.version && saved.panel_version !== saved.install.version
                  ? ` · running ${saved.panel_version}, run ./scripts/update-panel.sh`
                  : null}
              </Text>
            </Stack>
          </Card>
        ) : null}

        <Card {...blockProps('settings.appearance')}>
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

        <Card {...blockProps('settings.install-origin')}>
          <Stack gap="sm">
            <Title order={4}>Server</Title>
            <Text size="sm" c="dimmed">
              rpcnode-server origin other hosts and containers use to fetch the jar
              and clients. In Docker do not use <Code>127.0.0.1</Code> — that is the
              container itself. Put the Docker host IP or DNS and the published
              port (<Code>:8094</Code>, or <Code>:8093</Code> through admin nginx).
              Stored in <Code>database/toolkit.db</Code>.
            </Text>
            {error && (
              <Alert color="red" title="Settings">
                {error}
              </Alert>
            )}
            {!loading && suggestHost ? (
              <Text size="sm" c="dimmed">
                From this browser that is{' '}
                <Code>{advertisedOrigin(suggestHost, SERVER_LISTEN_PORT)}</Code>
                {' · '}
                <Button
                  size="compact-xs"
                  variant="light"
                  onClick={() => {
                    setOrigin(advertisedOrigin(suggestHost, SERVER_LISTEN_PORT))
                    if (!snapshotCdnOrigin || isLoopbackHost(originHost(snapshotCdnOrigin))) {
                      setSnapshotCdnOrigin(advertisedOrigin(suggestHost, CDN_LISTEN_PORT))
                    }
                  }}
                >
                  Use host
                </Button>
              </Text>
            ) : null}
            {isLoopbackHost(originHost(origin)) ? (
              <Alert color="yellow" title="127.0.0.1 will not work from Docker">
                Another container hitting 127.0.0.1 talks to itself. Use the Docker host
                IP or DNS and published port 8094.
              </Alert>
            ) : null}
            {!loading && (
              <ChannelOriginFields origin={origin} onChange={setOrigin} presets={saved?.presets} />
            )}
            <ChannelLinks
              links={saved?.links}
              curl={saved?.curl}
              scripts={saved?.scripts}
              panelScripts={saved?.panel_scripts}
            />
            <Group>
              <Button color="teal" loading={saving} disabled={loading} onClick={() => void save()}>
                Save origin
              </Button>
            </Group>
          </Stack>
        </Card>

        <Card {...blockProps('settings.snapshot-cdn')}>
          <Stack gap="sm">
            <Group justify="space-between" align="flex-start" wrap="wrap">
              <div>
                <Title order={4}>Snapshot CDN</Title>
                <Text size="sm" c="dimmed">
                  Public origin for mirrored node snapshots (site + <Code>/snapshots/</Code>). Empty
                  = prefer official mirrors only. Probe badge checks the origin root.
                </Text>
              </div>
              {!loading && saved?.snapshot_cdn_origin ? (
                <Badge color={saved.snapshot_cdn?.ok ? 'teal' : 'yellow'} variant="light">
                  {saved.snapshot_cdn?.ok ? 'Up' : 'Down'}
                </Badge>
              ) : !loading ? (
                <Badge color="gray" variant="light">
                  Off
                </Badge>
              ) : null}
            </Group>
            {snapshotCdnError ? (
              <Alert color="red" title="Snapshot CDN">
                {snapshotCdnError}
              </Alert>
            ) : null}
            <TextInput
              label="Snapshot CDN origin"
              description="Published CDN origin, e.g. http://10.0.0.2:8095 — not 127.0.0.1 in Docker. Blank = official mirrors only."
              placeholder="http://<host>:8095"
              value={snapshotCdnOrigin}
              onChange={(e) => setSnapshotCdnOrigin(e.currentTarget.value.trim())}
              disabled={loading || savingSnapshotCdn}
            />
            <Text size="sm" c="dimmed">
              Host nginx from <Code>deploy/nginx-cdn</Code>, then{' '}
              <Code>sudo ./scripts/install-rpcnode-cdn.sh</Code>.
            </Text>
            <Group>
              <Button
                color="teal"
                loading={savingSnapshotCdn}
                disabled={loading}
                onClick={() => void saveSnapshotCdn()}
              >
                Save Snapshot CDN
              </Button>
            </Group>
          </Stack>
        </Card>

        <Card {...blockProps('settings.github-token')}>
          <Stack gap="sm">
            <Group justify="space-between" align="flex-start" wrap="wrap">
              <div>
                <Title order={4}>GitHub token</Title>
                <Text size="sm" c="dimmed">
                  Required before Clients can probe or download releases (avoids unauthenticated API
                  limits). Classic PAT, public-repo read is enough.{' '}
                  <Anchor
                    href={saved?.github_token_create_url || 'https://github.com/settings/tokens/new'}
                    target="_blank"
                    rel="noreferrer"
                  >
                    Create a token
                  </Anchor>
                  . Encrypted in <Code>toolkit.db</Code> (
                  <Code>github_token_enc</Code>
                  ), plaintext copy for workers in <Code>database/github-token</Code>.
                </Text>
              </div>
              {!loading && saved?.github_token_set && saved.github_token_decrypt_ok !== false ? (
                <Badge color="teal" variant="light">
                  Saved {saved.github_token_masked || '••••'}
                </Badge>
              ) : !loading && saved?.github_token_set ? (
                <Badge color="red" variant="light">
                  Cannot decrypt
                </Badge>
              ) : !loading ? (
                <Badge color="yellow" variant="light">
                  Not set
                </Badge>
              ) : null}
            </Group>
            {saved?.github_token_set && saved.github_token_decrypt_ok === false ? (
              <Alert color="red" title="GitHub token">
                Cannot decrypt the stored token (missing/rotated <Code>panel.notify.key</Code>).
                Paste it again.
              </Alert>
            ) : null}
            {saved?.github_token_set && saved.github_token_decrypt_ok !== false && saved.github_token_masked ? (
              <Alert color="teal" title="GitHub token">
                Saved as <Code>{saved.github_token_masked}</Code>. The input
                stays empty — paste a new token only to replace it.
              </Alert>
            ) : null}
            {tokenError ? (
              <Alert color="red" title="GitHub token">
                {tokenError}
              </Alert>
            ) : null}
            {saved?.warning ? (
              <Alert color="orange" title="Origin">
                {saved.warning}
              </Alert>
            ) : null}
            <PasswordInput
              label="Personal access token"
              description={
                saved?.github_token_set && saved.github_token_decrypt_ok !== false
                  ? `Current token ${saved.github_token_masked || '••••'} — paste a new one to replace`
                  : 'Paste ghp_… or github_pat_…'
              }
              placeholder={saved?.github_token_masked || 'ghp_…'}
              value={githubToken}
              onChange={(e) => setGithubToken(e.currentTarget.value)}
              disabled={loading || savingToken}
            />
            <Group>
              <Button
                color="teal"
                loading={savingToken}
                disabled={loading || !githubToken.trim()}
                onClick={() => void saveGithubToken()}
              >
                Save GitHub token
              </Button>
              {saved?.github_token_set ? (
                <Button
                  variant="default"
                  disabled={loading || savingToken}
                  onClick={() => void clearGithubToken()}
                >
                  Clear
                </Button>
              ) : null}
            </Group>
          </Stack>
        </Card>

        <Card {...blockProps('settings.hierarchy')}>
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
