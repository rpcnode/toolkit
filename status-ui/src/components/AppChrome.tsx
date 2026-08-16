import { Group, Text, Title } from '@mantine/core'
import type { ReactNode } from 'react'

type Props = {
  title: string
  subtitle?: ReactNode
  /** Top-right: action buttons (same row as title). */
  right?: ReactNode
  /** Bottom-right: meta/endpoint (same row as subtitle). */
  rightMeta?: ReactNode
  children: ReactNode
}

/** Page chrome inside AppShell — two header rows when subtitle/rightMeta set. */
export function AppChrome({ title, subtitle, right, rightMeta, children }: Props) {
  const hasSecondRow = subtitle != null || rightMeta != null

  return (
    <div className="page-chrome">
      <header className="page-chrome__header">
        <Group
          justify="space-between"
          align="center"
          wrap="nowrap"
          gap="sm"
          className="page-chrome__row"
        >
          <Title order={2} style={{ minWidth: 0 }}>
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
