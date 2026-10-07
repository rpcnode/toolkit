import { Group, Text, Title } from '@mantine/core'
import type { ReactNode } from 'react'
import { blockProps, type BlockId } from '../lib/blockId'
import { PageAside } from './PageAside'

type Props = {
  /** Page root block id, e.g. `settings`, `nodes`. */
  block?: BlockId
  title: string
  subtitle?: ReactNode
  /** Top-right: action buttons (same row as title). */
  right?: ReactNode
  /** Bottom-right: meta/endpoint (same row as subtitle). */
  rightMeta?: ReactNode
  /**
   * Context pane on the right of the shell — logs, commands, docs for this
   * page. Same split as the installer: what you act on stays left, what you
   * read while acting stays right instead of pushing the page down.
   */
  aside?: ReactNode
  children: ReactNode
}

/** Page chrome inside AppShell — two header rows when subtitle/rightMeta set. */
export function AppChrome({ block, title, subtitle, right, rightMeta, aside, children }: Props) {
  const hasSecondRow = subtitle != null || rightMeta != null
  const root = block ? blockProps(block) : undefined

  return (
    <div className="page-chrome" {...root}>
      <header className="page-chrome__header" {...(block ? blockProps(`${block}.header`) : undefined)}>
        <Group
          justify="space-between"
          align="center"
          wrap="wrap"
          gap="sm"
          className="page-chrome__row"
        >
          <Title order={2} className="page-chrome__title" style={{ minWidth: 0, flex: '1 1 12rem' }}>
            {title}
          </Title>
          {right ? <div className="page-chrome__right">{right}</div> : null}
        </Group>
        {hasSecondRow ? (
          <Group
            justify="space-between"
            align="center"
            wrap="wrap"
            gap="sm"
            mt={6}
            className="page-chrome__row page-chrome__row--meta"
          >
            <div className="page-chrome__subtitle" style={{ minWidth: 0, flex: '1 1 auto' }}>
              {subtitle}
            </div>
            {rightMeta ? <div className="page-chrome__right-meta">{rightMeta}</div> : null}
          </Group>
        ) : null}
      </header>

      <PageAside>{aside}</PageAside>
      {children}
    </div>
  )
}

export function PageHint({ children }: { children: ReactNode }) {
  return (
    <Text c="dimmed" size="sm">
      {children}
    </Text>
  )
}

/**
 * AsideSection — one block in the right pane: hairline rule + lowercase label,
 * so several blocks read as one column instead of stacked cards.
 */
export function AsideSection({
  block,
  label,
  children,
}: {
  block?: BlockId
  label: string
  children: ReactNode
}) {
  return (
    <section className="aside-section" {...(block ? blockProps(block) : undefined)}>
      <h3 className="aside-section__label">{label}</h3>
      {children}
    </section>
  )
}
