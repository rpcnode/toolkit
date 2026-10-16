import { describe, expect, it } from 'vitest'
import { agentSnapshotRunning, snapshotBlockMessage, snapshotDownloadLive } from './nodeLifecycle'
import type { StatusPayload } from '../types'

const status = (over: Record<string, unknown>) =>
  ({ node_status: 'snapshot_error', lifecycle: { node_status: 'snapshot_error' }, ...over }) as unknown as StatusPayload

describe('a live agent snapshot beats a stale snapshot_error status', () => {
  const live = status({ snapshot: { busy: true, phase: 'download', pct: 0.9 } })

  it('is seen as running, shows no failure, and keeps the progress bar', () => {
    expect(agentSnapshotRunning(live)).toBe(true)
    expect(snapshotBlockMessage(live)).toBe('')
    expect(snapshotDownloadLive(live)).toBe(true)
  })

  it('still fails when the agent itself says failed, or nothing is running', () => {
    const failed = status({ snapshot: { busy: false, failed: true, phase: 'failed', error: 'disk full' } })
    expect(agentSnapshotRunning(failed)).toBe(false)
    expect(snapshotBlockMessage(failed)).toBe('disk full')
    expect(snapshotBlockMessage(status({ snapshot: { phase: 'idle' } }))).toContain('Snapshot failed')
  })
})
