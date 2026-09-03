import type { InstallOptionGroup } from '../InstallOptionsPicker'
import type { WizardStepId } from './types'

export const STEPS_TRON: { id: WizardStepId; label: string; blurb: string }[] = [
  { id: 'ports', label: 'Check ports', blurb: 'Tip catalog ports' },
  { id: 'disks', label: 'Disks', blurb: 'Layout & file limits' },
  { id: 'clients', label: 'Clients', blurb: 'Sync binaries to host' },
  { id: 'snapshot', label: 'Snapshot', blurb: 'Download chain data' },
  { id: 'start', label: 'Start', blurb: 'Launch node' },
  { id: 'sync', label: 'Sync', blurb: 'Catch up to tip' },
  { id: 'done', label: 'Finish', blurb: 'Node online' },
]

export const STEPS_NO_SNAP: { id: WizardStepId; label: string; blurb: string }[] = [
  { id: 'ports', label: 'Check ports', blurb: 'Tip catalog ports' },
  { id: 'disks', label: 'Disks', blurb: 'Layout & file limits' },
  { id: 'clients', label: 'Clients', blurb: 'Sync binaries to host' },
  { id: 'start', label: 'Start', blurb: 'Launch node' },
  { id: 'sync', label: 'Sync', blurb: 'Catch up to tip' },
  { id: 'done', label: 'Finish', blurb: 'Node ready' },
]

export const NODE_TYPE_STEP: { id: WizardStepId; label: string; blurb: string } = {
  id: 'node_type',
  label: 'Node type',
  blurb: 'Full / archive / history',
}

/** Install-option groups chosen on the Node type step (not Snapshot). */
export function isNodeTypeOptionGroup(g: InstallOptionGroup): boolean {
  return g.id !== 'snapshot'
}

export function wizardSteps(allowSnapshot: boolean, withNodeType: boolean) {
  const base = allowSnapshot ? STEPS_TRON : STEPS_NO_SNAP
  if (!withNodeType) return base
  const disksIdx = base.findIndex((s) => s.id === 'disks')
  if (disksIdx < 0) return [...base.slice(0, 1), NODE_TYPE_STEP, ...base.slice(1)]
  return [...base.slice(0, disksIdx + 1), NODE_TYPE_STEP, ...base.slice(disksIdx + 1)]
}

export function stepIndex(id: WizardStepId, allowSnapshot: boolean, withNodeType = false): number {
  return wizardSteps(allowSnapshot, withNodeType).findIndex((s) => s.id === id)
}

/** Agent lifecycle still has `install`; the wizard rail goes Disks → Clients → Snapshot/Start. */
export function wizardVisibleStep(
  step: WizardStepId | null,
  allowSnapshot: boolean,
): WizardStepId | null {
  if (!step) return null
  if (step === 'install') return allowSnapshot ? 'snapshot' : 'start'
  return step
}

/**
 * Panel SQLite status survives refresh; agent lifecycle / local refs do not.
 * Map it so reload does not drop back to Check ports after Disks → Clients → Snapshot.
 */
export function wizardStepFromPanelStatus(
  wlStatus: string | null | undefined,
  allowSnapshot: boolean,
): WizardStepId | null {
  const wl = (wlStatus || '').toLowerCase()
  if (!wl) return null
  if (
    wl === 'awaiting_ports' ||
    wl === 'ports_pending' ||
    wl === 'ports_confirmed' ||
    wl === 'ready_to_install'
  ) {
    return 'ports'
  }
  if (wl === 'needs_clients' || wl === 'clients_error') {
    return 'clients'
  }
  if (
    allowSnapshot &&
    (wl === 'needs_snapshot' ||
      wl === 'snapshot_running' ||
      wl === 'snapshot_error' ||
      wl === 'snapshot_complete')
  ) {
    return wl === 'snapshot_complete' ? 'start' : 'snapshot'
  }
  if (
    wl === 'starting' ||
    wl === 'start_error' ||
    wl === 'ready_to_start' ||
    wl === 'installing'
  ) {
    return 'start'
  }
  if (wl === 'sync' || wl === 'syncing' || wl === 'stopped') {
    return 'sync'
  }
  if (wl === 'active' || wl === 'running' || wl === 'working') {
    return 'done'
  }
  return null
}
