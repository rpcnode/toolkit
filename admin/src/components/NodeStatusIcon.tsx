import { IconAlertTriangle, IconCheck, IconTopologyStar3 } from '@tabler/icons-react'

export type NodeStatusIconProps = {
  phase: string
  busy?: boolean
}

type Tone = 'synced' | 'sync' | 'setup' | 'warn' | 'error' | 'idle'

function toneFor(phase: string, busy?: boolean): Tone {
  const p = (phase || '').toLowerCase()
  if (p === 'working') return 'synced'
  if (p === 'error') return 'error'
  if (p === 'removing' || p === 'remove_error') return 'warn'
  if (p === 'syncing') return 'sync'
  if (
    p === 'installing' ||
    p === 'starting' ||
    p === 'setup' ||
    p === 'updating' ||
    p === 'restarting' ||
    p === 'stopping'
  ) {
    return 'setup'
  }
  if (busy) return 'sync'
  return 'idle'
}

export function NodeStatusIcon({ phase, busy }: NodeStatusIconProps) {
  const tone = toneFor(phase, busy)
  const spinning = !!busy && tone !== 'synced' && tone !== 'error'

  return (
    <span
      className={`node-status-icon node-status-icon--${tone}${spinning ? ' is-busy' : ''}`}
      aria-hidden
    >
      {spinning ? (
        <span className="node-status-icon__spin" />
      ) : tone === 'synced' ? (
        <IconCheck size={16} stroke={2} />
      ) : tone === 'error' ? (
        <IconAlertTriangle size={16} stroke={1.75} />
      ) : (
        <IconTopologyStar3 size={16} stroke={1.5} />
      )}
    </span>
  )
}

/** Tiny spinner for badges — same stroke as the card icon. */
export function NodeStatusSpin({ size = 'xs' }: { size?: 'xs' | 'sm' }) {
  return <span className={`node-status-icon__spin node-status-icon__spin--${size}`} aria-hidden />
}
