import {
  ActionIcon,
  Alert,
  Anchor,
  AppShell,
  Burger,
  Code,
  Group,
  NavLink,
  Text,
  Divider,
  Stack,
  Tooltip,
  UnstyledButton,
} from '@mantine/core'
import { useDisclosure } from '@mantine/hooks'
import {
  IconAlertTriangle,
  IconExternalLink,
  IconHome,
  IconServer,
  IconTopologyStar3,
  IconLogout,
  IconHeartHandshake,
  IconBell,
} from '@tabler/icons-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { Route } from '../lib/router'
import { navigate } from '../lib/router'
import { api } from '../api'
import { BrandLogo } from './BrandLogo'
import { ThemeToggle } from './ThemeToggle'
import { AddServerModal } from './AddServerModal'
import { ApiDocsButton, ApiDocsModal } from './ApiDocsModal'
import { DonateModal } from './DonateModal'
import { PANEL_VERSION } from '../panelVersion'

const RPCNODE_URL = 'https://rpcnode.dev'
const CONTACT_EMAIL = 'admin@rpcnode.dev'

const MAIN_NAV = [
  { name: 'dashboard', label: 'Dashboard', icon: IconHome },
  { name: 'servers', label: 'Servers', icon: IconServer },
  { name: 'nodes', label: 'Nodes', icon: IconTopologyStar3 },
  { name: 'notifications', label: 'Notifications', icon: IconBell },
] as const

function CollectorStaleBanner() {
  const [stale, setStale] = useState(false)
  const [ageSec, setAgeSec] = useState<number | null>(null)
  const emptySinceRef = useRef<number | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const s = await api.collectorStats()
        if (cancelled) return
        const age = typeof s.age_sec === 'number' ? s.age_sec : -1
        setAgeSec(age)
        if (!s.stale) {
          emptySinceRef.current = null
          setStale(false)
          return
        }
        if (s.has_tick) {
          setStale(true)
          return
        }
        if (emptySinceRef.current == null) emptySinceRef.current = Date.now()
        setStale(Date.now() - emptySinceRef.current > 120_000)
      } catch {
        /* panel itself down — this SPA would not keep polling */
      }
    }
    void load()
    const id = window.setInterval(() => void load(), 15_000)
    return () => {
      cancelled = true
      window.clearInterval(id)
    }
  }, [])

  if (!stale) return null

  const ageLabel = ageSec != null && ageSec >= 0 ? ` Last write ${ageSec}s ago.` : ''

  return (
    <Alert
      className="panel-collector-dead"
      color="red"
      variant="filled"
      icon={<IconAlertTriangle size={18} />}
      title="Panel collector is not updating"
    >
      Status data has not been written for more than 2 minutes — the collector process is likely
      stuck or dead.{ageLabel} Docker <Code>rpcnode-panel-watchdog</Code> should restart{' '}
      <Code>rpcnode-panel-collector</Code> automatically. If this banner stays:
      <br />
      <Code>docker restart rpcnode-panel-collector</Code>
      {' · '}
      <Code>docker restart rpcnode-panel</Code> if the UI itself is frozen.
    </Alert>
  )
}

type Props = {
  route: Route
  children: ReactNode
  /** Force-open Install agent modal (e.g. /install bookmark). */
  openInstall?: boolean
}

export function AppShellLayout({ route, children, openInstall = false }: Props) {
  const [navOpened, { toggle: toggleNav, close: closeNav }] = useDisclosure()
  const [installOpen, setInstallOpen] = useState(openInstall)
  const [apiOpen, setApiOpen] = useState(false)
  const [donateOpen, setDonateOpen] = useState(false)
  const [publicBase, setPublicBase] = useState(window.location.origin)

  useEffect(() => {
    setInstallOpen(openInstall)
  }, [openInstall])

  useEffect(() => {
    void api
      .status()
      .then((st) => {
        if (st?.connect?.panel_base) setPublicBase(st.connect.panel_base)
      })
      .catch(() => {
        /* offline ok */
      })
  }, [])

  function go(name: (typeof MAIN_NAV)[number]['name']) {
    closeNav()
    navigate({ name })
  }

  function closeInstall() {
    setInstallOpen(false)
    if (window.location.pathname === '/install' || window.location.pathname === '/setup') {
      navigate({ name: 'servers' })
    }
  }

  async function logout() {
    try {
      await api.logout()
    } catch {
      /* ignore */
    }
    navigate({ name: 'login' })
    window.location.href = '/login'
  }

  const active = route.name === 'install' ? 'servers' : route.name === 'node' ? 'nodes' : route.name

  return (
    <>
      <AppShell
        header={{ height: 56, offset: true }}
        navbar={{
          width: 240,
          breakpoint: 'sm',
          collapsed: { mobile: !navOpened, desktop: true },
        }}
        padding={{ base: 'md', sm: 'lg' }}
        classNames={{
          root: 'panel-shell',
          header: 'panel-header',
          navbar: 'panel-navbar',
          main: 'panel-main',
        }}
        styles={{
          root: { height: '100dvh', maxHeight: '100dvh', overflow: 'hidden' },
          header: { zIndex: 200 },
          navbar: {
            zIndex: 150,
            display: 'flex',
            flexDirection: 'column',
            overflow: 'hidden',
          },
          main: {
            overflowY: 'auto',
            overflowX: 'hidden',
            height: '100dvh',
            maxHeight: '100dvh',
          },
        }}
      >
        <AppShell.Header>
          <Group h="100%" px="md" justify="space-between" wrap="nowrap" gap="sm">
            <Group gap="md" wrap="nowrap" style={{ minWidth: 0, flex: 1 }}>
              <Burger opened={navOpened} onClick={toggleNav} hiddenFrom="sm" size="sm" aria-label="Open menu" />
              <Anchor
                href={RPCNODE_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="brand-mark"
                underline="hover"
              >
                <BrandLogo size={22} />
                RpcNode
                <IconExternalLink size={12} className="brand-mark__icon" aria-hidden />
              </Anchor>
              <nav className="panel-topnav" aria-label="Main">
                <Group gap={2} wrap="nowrap" visibleFrom="sm">
                  {MAIN_NAV.map((item) => {
                    const Icon = item.icon
                    const isActive = active === item.name
                    return (
                      <UnstyledButton
                        key={item.name}
                        className={`panel-topnav__item${isActive ? ' panel-topnav__item--active' : ''}`}
                        onClick={() => go(item.name)}
                        aria-current={isActive ? 'page' : undefined}
                      >
                        <Icon size={16} stroke={1.6} aria-hidden />
                        <span className="panel-topnav__label">{item.label}</span>
                      </UnstyledButton>
                    )
                  })}
                </Group>
              </nav>
            </Group>
            <Group gap={6} wrap="nowrap">
              <Anchor
                component="button"
                type="button"
                size="sm"
                c="dimmed"
                underline="hover"
                visibleFrom="md"
                onClick={() => setDonateOpen(true)}
                style={{ background: 'none', border: 0, cursor: 'pointer', padding: 0 }}
              >
                <Group gap={4} wrap="nowrap">
                  <IconHeartHandshake size={14} stroke={1.5} aria-hidden />
                  Donate
                </Group>
              </Anchor>
              <ThemeToggle />
              <ApiDocsButton onClick={() => setApiOpen(true)} />
              <Tooltip label="Log out" withArrow>
                <ActionIcon
                  variant="subtle"
                  color="gray"
                  aria-label="Log out"
                  visibleFrom="sm"
                  onClick={() => void logout()}
                >
                  <IconLogout size={16} stroke={1.6} />
                </ActionIcon>
              </Tooltip>
            </Group>
          </Group>
        </AppShell.Header>

        <AppShell.Navbar p="md" className="panel-navbar">
          <AppShell.Section grow>
            <Stack gap={4}>
              {MAIN_NAV.map((item) => {
                const Icon = item.icon
                return (
                  <NavLink
                    key={item.name}
                    label={item.label}
                    leftSection={<Icon size={18} stroke={1.5} />}
                    active={active === item.name}
                    onClick={() => go(item.name)}
                  />
                )
              })}
            </Stack>
          </AppShell.Section>

          <AppShell.Section>
            <Divider mb="sm" />
            <NavLink
              label="Log out"
              leftSection={<IconLogout size={18} stroke={1.5} />}
              onClick={() => void logout()}
              color="gray"
            />
          </AppShell.Section>
        </AppShell.Navbar>

        <AppShell.Main className="panel-main">
          <CollectorStaleBanner />
          {children}
          <footer className="console-footer">
            <Text size="sm" c="dimmed">
              Powered by{' '}
              <Anchor href={RPCNODE_URL} target="_blank" rel="noopener noreferrer" size="sm" fw={600}>
                RpcNode
              </Anchor>
              {' · '}
              <Anchor href={`mailto:${CONTACT_EMAIL}`} size="sm" fw={600}>
                {CONTACT_EMAIL}
              </Anchor>
              {' · '}
              <Anchor
                component="button"
                type="button"
                size="sm"
                fw={600}
                onClick={() => setDonateOpen(true)}
                style={{ background: 'none', border: 0, cursor: 'pointer', padding: 0 }}
              >
                Donate
              </Anchor>
              {' · '}
              {PANEL_VERSION}
            </Text>
          </footer>
        </AppShell.Main>
      </AppShell>

      <AddServerModal opened={installOpen} onClose={closeInstall} />
      <ApiDocsModal opened={apiOpen} onClose={() => setApiOpen(false)} baseUrl={publicBase} />
      <DonateModal opened={donateOpen} onClose={() => setDonateOpen(false)} />
    </>
  )
}
