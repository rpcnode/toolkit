import {
  ActionIcon,
  Anchor,
  AppShell,
  Burger,
  Group,
  Text,
  Tooltip,
} from '@mantine/core'
import { useDisclosure, useMediaQuery } from '@mantine/hooks'
import {
  IconExternalLink,
  IconHome,
  IconServer,
  IconTopologyStar3,
  IconLogout,
  IconHeartHandshake,
  IconBell,
  IconPackage,
  IconBrandGithub,
  IconSettings,
  IconWorld,
  IconX,
} from '@tabler/icons-react'
import { useEffect, useState, type MouseEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import type { Route } from '../lib/router'
import { hrefFor, navigate } from '../lib/router'
import { api } from '../api'
import { BrandLogo } from './BrandLogo'
import { ThemeToggle } from './ThemeToggle'
import { AddServerModal } from './AddServerModal'
import { ApiDocsButton, ApiDocsModal } from './ApiDocsModal'
import { DonateModal } from './DonateModal'
import { PANEL_VERSION } from '../panelVersion'
import { TipAgentVersion } from './AgentVersion'
import { PageAsideProvider, useAsideShell } from './PageAside'
import { blockProps } from '../lib/blockId'

const RPCNODE_URL = 'https://rpcnode.dev'
const GITHUB_URL = 'https://github.com/rpcnode/toolkit'
const CONTACT_EMAIL = 'admin@rpcnode.dev'

const MAIN_NAV = [
  { name: 'dashboard', label: 'dashboard', icon: IconHome },
  { name: 'servers', label: 'servers', icon: IconServer },
  { name: 'nodes', label: 'nodes', icon: IconTopologyStar3 },
  { name: 'networks', label: 'networks', icon: IconWorld },
  { name: 'clients', label: 'clients', icon: IconPackage },
  { name: 'notifications', label: 'notifications', icon: IconBell },
  { name: 'settings', label: 'settings', icon: IconSettings },
] as const

type Props = {
  route: Route
  children: ReactNode
  /** Force-open Install agent modal (e.g. /install bookmark). */
  openInstall?: boolean
}

export function AppShellLayout(props: Props) {
  return (
    <PageAsideProvider>
      <AppShellInner {...props} />
    </PageAsideProvider>
  )
}

function AppShellInner({ route, children, openInstall = false }: Props) {
  const { setHost, wanted, mobileOpen, setMobileOpen } = useAsideShell()
  const asideOverlay = useMediaQuery('(max-width: 75em)') ?? false
  const [navOpened, { toggle: toggleNav, close: closeNav }] = useDisclosure()
  const [installOpen, setInstallOpen] = useState(openInstall)
  const [apiOpen, setApiOpen] = useState(false)
  const [donateOpen, setDonateOpen] = useState(false)
  const [publicBase, setPublicBase] = useState(window.location.origin)

  useEffect(() => {
    setInstallOpen(openInstall)
  }, [openInstall])

  useEffect(() => {
    setMobileOpen(false)
  }, [route.name, route.name === 'node' ? route.id : '', setMobileOpen])

  useEffect(() => {
    if (!mobileOpen || !wanted || !asideOverlay) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMobileOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [mobileOpen, wanted, asideOverlay, setMobileOpen])

  useEffect(() => {
    if (!mobileOpen || !wanted || !asideOverlay) return
    const prev = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = prev
    }
  }, [mobileOpen, wanted, asideOverlay])

  useEffect(() => {
    void api
      .publicBaseGet()
      .then((res) => {
        if (res?.panel_base) setPublicBase(res.panel_base)
      })
      .catch(() => {
        /* offline ok */
      })
  }, [])

  function go(name: (typeof MAIN_NAV)[number]['name'], e?: MouseEvent<HTMLAnchorElement>) {
    // Real <a href> for middle-click / open-in-new-tab; SPA navigate on plain left click.
    if (e && (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0)) {
      closeNav()
      return
    }
    e?.preventDefault()
    closeNav()
    navigate({ name })
  }

  function closeInstall() {
    setInstallOpen(false)
    if (window.location.pathname === '/install') {
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
  const pageLabel = MAIN_NAV.find((i) => i.name === active)?.label || String(route.name)

  return (
    <>
      <AppShell
        {...blockProps('shell')}
        header={{ height: 44, offset: true }}
        navbar={{
          width: 208,
          breakpoint: 'sm',
          collapsed: { mobile: !navOpened, desktop: false },
        }}
        aside={{
          width: 360,
          breakpoint: 'lg',
          collapsed: { mobile: !wanted || !mobileOpen, desktop: !wanted },
        }}
        footer={{ height: 28, offset: true }}
        padding={{ base: 'md', sm: 'lg' }}
        classNames={{
          root: 'panel-shell',
          header: 'panel-header',
          navbar: 'panel-navbar',
          aside: 'panel-aside',
          footer: 'panel-statusbar',
          main: 'panel-main',
        }}
      >
        <AppShell.Header {...blockProps('shell.header')}>
          <Group h="100%" px="sm" justify="space-between" wrap="nowrap" gap="sm">
            <Group gap="xs" wrap="nowrap" style={{ minWidth: 0, flex: 1 }}>
              <Burger opened={navOpened} onClick={toggleNav} hiddenFrom="sm" size="sm" aria-label="Open menu" />
              <Anchor
                href={RPCNODE_URL}
                target="_blank"
                rel="noopener noreferrer"
                className="panel-id"
                underline="never"
              >
                <BrandLogo size={16} />
                <span className="panel-id__mark">rpcnode</span>
                <IconExternalLink size={11} className="panel-id__ext" aria-hidden />
              </Anchor>
              <span className="panel-id__slash">/</span>
              <span className="panel-id__page">{pageLabel}</span>
            </Group>
            <Group gap={4} wrap="nowrap">
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

        <AppShell.Navbar className="panel-navbar" {...blockProps('shell.nav')}>
          <AppShell.Section grow>
            <nav aria-label="Main">
              <ol className="panel-rail">
                {MAIN_NAV.map((item, i) => {
                  const Icon = item.icon
                  const isActive = active === item.name
                  const href = hrefFor({ name: item.name })
                  return (
                    <li key={item.name}>
                      <a
                        href={href}
                        className={`panel-rail__item${isActive ? ' is-active' : ''}`}
                        onClick={(e) => go(item.name, e)}
                        aria-current={isActive ? 'page' : undefined}
                      >
                        <span className="panel-rail__n">{String(i + 1).padStart(2, '0')}</span>
                        <Icon size={15} stroke={1.6} aria-hidden />
                        <span className="panel-rail__label">{item.label}</span>
                      </a>
                    </li>
                  )
                })}
              </ol>
            </nav>
          </AppShell.Section>

          <AppShell.Section className="panel-rail__foot">
            <Anchor
              href={GITHUB_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="panel-rail__link"
              underline="never"
            >
              <IconBrandGithub size={14} stroke={1.6} aria-hidden /> github
            </Anchor>
            <Anchor
              component="button"
              type="button"
              className="panel-rail__link"
              underline="never"
              onClick={() => setDonateOpen(true)}
            >
              <IconHeartHandshake size={14} stroke={1.6} aria-hidden /> donate
            </Anchor>
            <Anchor href={`mailto:${CONTACT_EMAIL}`} className="panel-rail__link" underline="never">
              {CONTACT_EMAIL}
            </Anchor>
          </AppShell.Section>
        </AppShell.Navbar>

        <AppShell.Main className="panel-main" {...blockProps('shell.main')}>
          {children}
        </AppShell.Main>

        <AppShell.Aside
          className={`panel-aside${mobileOpen && wanted && asideOverlay ? ' panel-aside--mobile-open' : ''}`}
          {...blockProps('shell.aside')}
        >
          {mobileOpen && wanted && asideOverlay ? (
            <Group className="panel-aside__toolbar" justify="space-between" wrap="nowrap" gap="sm">
              <Text size="sm" fw={600} tt="lowercase" className="panel-aside__toolbar-title">
                details
              </Text>
              <Tooltip label="Close panel" withArrow>
                <ActionIcon
                  variant="subtle"
                  color="gray"
                  size="lg"
                  aria-label="Close details panel"
                  onClick={() => setMobileOpen(false)}
                >
                  <IconX size={18} stroke={1.8} />
                </ActionIcon>
              </Tooltip>
            </Group>
          ) : null}
          <div className="panel-aside__body" ref={setHost} />
        </AppShell.Aside>

        {mobileOpen && wanted && asideOverlay
          ? createPortal(
              <button
                type="button"
                className="panel-aside-backdrop"
                aria-label="Close details panel"
                onClick={() => setMobileOpen(false)}
              />,
              document.body,
            )
          : null}

        <AppShell.Footer className="panel-statusbar" {...blockProps('shell.footer')}>
          <Text span className="panel-statusbar__left" title={`panel ${PANEL_VERSION}`}>
            panel {PANEL_VERSION}
            <TipAgentVersion />
          </Text>
          <Text span className="panel-statusbar__right">
            {pageLabel}
          </Text>
        </AppShell.Footer>
      </AppShell>

      <AddServerModal opened={installOpen} onClose={closeInstall} />
      <ApiDocsModal opened={apiOpen} onClose={() => setApiOpen(false)} baseUrl={publicBase} />
      <DonateModal opened={donateOpen} onClose={() => setDonateOpen(false)} />
    </>
  )
}
