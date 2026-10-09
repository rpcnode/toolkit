import type { ReactNode } from 'react'
import { ThemeToggle } from './ThemeToggle'
import { blockProps } from '../lib/blockId'
import type { BlockId } from '../lib/blockId'

export function SetupShell({
  block,
  title = 'setup',
  index,
  status,
  left,
  right,
}: {
  block?: BlockId
  title?: string
  index?: string
  status?: string
  left: ReactNode
  right: ReactNode
}) {
  return (
    <div className="setup-shell" {...(block ? blockProps(block) : undefined)}>
      <div className="setup-grid" aria-hidden />
      <header className="setup-top" {...blockProps(`${block || 'setup'}.header`)}>
        <div className="setup-top__id">
          <span className="setup-top__mark">rpcnode</span>
          <span className="setup-top__slash">/</span>
          <span className="setup-top__title">{title}</span>
          {index ? <span className="setup-top__idx">{index}</span> : null}
        </div>
        <ThemeToggle />
      </header>
      <div className="setup-frame">
        <section className="setup-pane setup-pane--form" {...blockProps(`${block || 'setup'}.form`)}>{left}</section>
        <aside className="setup-pane setup-pane--info" {...blockProps(`${block || 'setup'}.doc`)}>{right}</aside>
      </div>
      <footer className="setup-bar" {...blockProps(`${block || 'setup'}.footer`)}>
        <span>{status || 'panel.install  ·  pending'}</span>
        <span className="setup-bar__keys">
          <kbd>esc</kbd> back <kbd>ret</kbd> next
        </span>
      </footer>
    </div>
  )
}

export function SetupCmd({
  children,
  onClick,
  type = 'button',
  ghost,
  busy,
  disabled,
}: {
  children: string
  onClick?: () => void
  type?: 'button' | 'submit'
  ghost?: boolean
  busy?: boolean
  disabled?: boolean
}) {
  return (
    <button
      type={type}
      className={`setup-cmd${ghost ? ' is-ghost' : ''}`}
      onClick={onClick}
      disabled={disabled || busy}
    >
      {busy ? '…' : children}
    </button>
  )
}
