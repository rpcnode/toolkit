import { useCallback, useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'
import { Center, Loader, Stack, Text } from '@mantine/core'
import { AppShellLayout } from './components/AppShellLayout'
import { HomePage } from './pages/HomePage'
import { ServersPage } from './pages/ServersPage'
import { NodesPage } from './pages/NodesPage'
import { EnvDetailPage } from './pages/EnvDetailPage'
import { LoginPage } from './pages/LoginPage'
import { SetupWizardPage } from './pages/SetupWizardPage'
import { SettingsPage } from './pages/SettingsPage'
import { NetworksPage } from './pages/NetworksPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { ClientsPage } from './pages/ClientsPage'
import { parseRoute, nodeIdToEnv, navigate } from './lib/router'
import { api, type AuthStatus } from './api'
import { loadNetworksCatalog } from './lib/networksCatalog'
import { blockProps } from './lib/blockId'

function subscribe(cb: () => void) {
  window.addEventListener('popstate', cb)
  return () => window.removeEventListener('popstate', cb)
}

function getPath() {
  return `${window.location.pathname}${window.location.search}`
}

function getServerPath() {
  return '/'
}

function isSetupPath(path: string) {
  return path === '/setup' || path === '/setup-password'
}

export function App() {
  const locationKey = useSyncExternalStore(subscribe, getPath, getServerPath)
  const [route, setRoute] = useState(() => parseRoute(window.location.pathname))
  const [auth, setAuth] = useState<AuthStatus | null>(null)
  const [setupNeeded, setSetupNeeded] = useState<boolean | null>(null)
  const [bootLoading, setBootLoading] = useState(true)
  const [catalogReady, setCatalogReady] = useState(false)

  const syncRoute = useCallback(() => {
    setRoute(parseRoute(window.location.pathname))
  }, [])

  const refresh = useCallback(async () => {
    try {
      const [st, su] = await Promise.all([
        api.authStatus(),
        api.setupStatus(),
      ])
      setAuth(st)
      setSetupNeeded(!!su.needed)
    } catch {
      setAuth((prev) => prev ?? { ok: false, authenticated: false })
      setSetupNeeded((prev) => prev ?? true)
    } finally {
      setBootLoading(false)
    }
  }, [])

  useEffect(() => {
    syncRoute()
  }, [locationKey, syncRoute])

  useEffect(() => {
    void refresh()
  }, [refresh, locationKey])

  useEffect(() => {
    if (!auth?.authenticated) {
      setCatalogReady(false)
      return
    }
    let cancelled = false
    void loadNetworksCatalog()
      .catch(() => [])
      .finally(() => {
        if (!cancelled) setCatalogReady(true)
      })
    return () => {
      cancelled = true
    }
  }, [auth?.authenticated])

  useEffect(() => {
    if (bootLoading || setupNeeded == null || !auth) return
    const path = window.location.pathname
    if (setupNeeded && !isSetupPath(path)) {
      navigate({ name: 'setup' })
      return
    }
    if (auth.authenticated && !setupNeeded && (path === '/login' || isSetupPath(path))) {
      navigate({ name: 'dashboard' })
      return
    }
    if (!setupNeeded && !auth.authenticated && path !== '/login') {
      navigate({ name: 'login' })
    }
  }, [auth, setupNeeded, bootLoading])

  if (bootLoading) {
    return (
      <Center mih="100vh" {...blockProps('app.boot')}>
        <Stack align="center" gap="sm">
          <Loader color="teal" />
          <Text c="dimmed">Loading panel…</Text>
        </Stack>
      </Center>
    )
  }

  if (setupNeeded) {
    return <SetupWizardPage />
  }

  if (!auth?.authenticated) {
    return (
      <LoginPage
        onAuthed={(st) => {
          setAuth(st)
          setSetupNeeded(false)
          navigate({ name: 'dashboard' })
        }}
      />
    )
  }

  if (!catalogReady) {
    return (
      <Center mih="100vh" {...blockProps('app.catalog-loading')}>
        <Stack align="center" gap="sm">
          <Loader color="teal" />
          <Text c="dimmed">Loading panel…</Text>
        </Stack>
      </Center>
    )
  }

  let page: ReactNode
  switch (route.name) {
    case 'servers':
      page = <ServersPage />
      break
    case 'nodes':
      page = <NodesPage />
      break
    case 'node':
      page = (
        <EnvDetailPage
          env={nodeIdToEnv(route.id) || 'mainnet'}
          nodeId={route.id}
          key={route.id}
        />
      )
      break
    case 'settings':
      page = <SettingsPage />
      break
    case 'clients':
      page = <ClientsPage />
      break
    case 'networks':
      page = <NetworksPage />
      break
    case 'notifications':
      page = <NotificationsPage />
      break
    case 'install':
      page = <ServersPage />
      break
    default:
      page = <HomePage />
  }

  return (
    <AppShellLayout route={route} openInstall={route.name === 'install'}>
      {page}
    </AppShellLayout>
  )
}
