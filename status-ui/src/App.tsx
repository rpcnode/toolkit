import { useCallback, useEffect, useState, useSyncExternalStore, type ReactNode } from 'react'
import { Center, Loader, Stack, Text } from '@mantine/core'
import { AppShellLayout } from './components/AppShellLayout'
import { HomePage } from './pages/HomePage'
import { ServersPage } from './pages/ServersPage'
import { NodesPage } from './pages/NodesPage'
import { EnvDetailPage } from './pages/EnvDetailPage'
import { LoginPage } from './pages/LoginPage'
import { SetupPasswordPage } from './pages/SetupPasswordPage'
import { NotificationsPage } from './pages/NotificationsPage'
import { parseRoute, nodeIdToEnv, navigate } from './lib/router'
import { api, type AuthStatus } from './api'

function subscribe(cb: () => void) {
  window.addEventListener('popstate', cb)
  return () => window.removeEventListener('popstate', cb)
}

/** Path + query so Nodes catalog filters re-render the shell nav. */
function getPath() {
  return `${window.location.pathname}${window.location.search}`
}

function getServerPath() {
  return '/'
}

export function App() {
  const locationKey = useSyncExternalStore(subscribe, getPath, getServerPath)
  const [route, setRoute] = useState(() => parseRoute(window.location.pathname))
  const [auth, setAuth] = useState<AuthStatus | null>(null)
  const [authLoading, setAuthLoading] = useState(true)

  const syncRoute = useCallback(() => {
    setRoute(parseRoute(window.location.pathname))
  }, [])

  const refreshAuth = useCallback(async () => {
    try {
      const st = await api.authStatus()
      setAuth(st)
    } catch {
      setAuth({ ok: false, needs_setup: true, authenticated: false })
    } finally {
      setAuthLoading(false)
    }
  }, [])

  useEffect(() => {
    syncRoute()
  }, [locationKey, syncRoute])

  useEffect(() => {
    void refreshAuth()
  }, [refreshAuth, locationKey])

  // Keep URL aligned with forced auth screens
  useEffect(() => {
    if (authLoading || !auth) return
    const path = window.location.pathname
    if (auth.needs_setup && path !== '/setup-password') {
      navigate({ name: 'setupPassword' })
      return
    }
    if (auth.authenticated && (path === '/login' || path === '/setup-password')) {
      navigate({ name: 'dashboard' })
      return
    }
    if (!auth.needs_setup && !auth.authenticated && path !== '/login') {
      navigate({ name: 'login' })
    }
  }, [auth, authLoading])

  if (authLoading) {
    return (
      <Center mih="100vh">
        <Stack align="center" gap="sm">
          <Loader color="teal" />
          <Text c="dimmed">Loading panel…</Text>
        </Stack>
      </Center>
    )
  }

  if (auth?.needs_setup) {
    return <SetupPasswordPage />
  }

  if (!auth?.authenticated) {
    return <LoginPage />
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
      // Settings removed from menu — keep route as redirect to dashboard.
      page = <HomePage />
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
