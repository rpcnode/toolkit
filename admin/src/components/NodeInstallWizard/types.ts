import type { RegistryNode, Workload } from '../../api'
import type { StatusPayload } from '../../types'

export type WizardStepId =
  | 'ports'
  | 'disks'
  | 'node_type'
  | 'clients'
  | 'install'
  | 'snapshot'
  | 'start'
  | 'sync'
  | 'done'

export type UnsupportedCapability = {
  error: 'unsupported_network' | 'unsupported_env'
  message: string
  agentVersion: string
}

export type PlannedPorts = {
  public_port: number
  agent_port: number
  node_http_port: number
  p2p_port: number
  captive_core_http_query_port?: number
  admin_port?: number
  drifted?: boolean
  source: 'agent' | 'workload' | 'catalog'
}

export type ClientConfigPreviewRow = {
  path: string
  value: string
  description: string
  source: string
  detail: string
  editable: boolean
  option?: string
  portToggle?: string
  alwaysOn?: boolean
  testConnect?: { kind: string; label: string; help?: string }
}

export type CatalogPortPolicy = {
  role?: string
  port?: number
  label?: string
  config?: string
  config_enabled_default?: boolean
}

export type SnapshotSpeedReading = {
  loading?: boolean
  available?: boolean
  bytes_per_sec?: number | null
  detail?: string | null
  error?: string | null
}

export type NodeInstallWizardProps = {
  env: string
  workload: Workload | null
  status: StatusPayload | null
  statusReady?: boolean
  serverLabel?: string | null
  serverURL?: string | null
  server?: RegistryNode | null
  onRefresh: () => Promise<void> | void
  onWorkloadUpdated?: () => Promise<void> | void
  onSetupComplete?: () => void
}

export type WizardStepDef = { id: WizardStepId; label: string; blurb: string }
