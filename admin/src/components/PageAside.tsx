import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { createPortal } from 'react-dom'

type AsideCtx = {
  /** DOM node of the right pane, null while no page asks for it. */
  host: HTMLElement | null
  setHost: (el: HTMLElement | null) => void
  /** True while a page has content for the pane — the shell renders it. */
  wanted: boolean
  setWanted: (on: boolean) => void
  /** Below AppShell aside breakpoint — operator toggles the overlay drawer. */
  mobileOpen: boolean
  setMobileOpen: (on: boolean) => void
  toggleMobile: () => void
}

const Ctx = createContext<AsideCtx | null>(null)

/**
 * PageAsideProvider — one right-hand context pane owned by the shell.
 *
 * The install wizard keeps the form on the left and the docs/commands on the
 * right, and that split is what makes it readable. Pages here render into the
 * same pane through a portal instead of each growing its own sidebar, so the
 * column is always in the same place and only exists when a page fills it.
 */
export function PageAsideProvider({ children }: { children: ReactNode }) {
  const [host, setHost] = useState<HTMLElement | null>(null)
  const [wanted, setWanted] = useState(false)
  const [mobileOpen, setMobileOpen] = useState(false)
  const toggleMobile = useCallback(() => setMobileOpen((v) => !v), [])
  const value = useMemo(
    () => ({ host, setHost, wanted, setWanted, mobileOpen, setMobileOpen, toggleMobile }),
    [host, wanted, mobileOpen, toggleMobile],
  )
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useAsideShell() {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useAsideShell outside PageAsideProvider')
  return ctx
}

/** PageAside renders page content into the shell's right pane. */
export function PageAside({ children }: { children?: ReactNode }) {
  const ctx = useContext(Ctx)
  const has = !!children
  const setWanted = ctx?.setWanted
  const on = useCallback(() => setWanted?.(has), [setWanted, has])
  useEffect(() => {
    on()
    return () => setWanted?.(false)
  }, [on, setWanted])
  if (!ctx?.host || !has) return null
  return createPortal(children, ctx.host)
}
