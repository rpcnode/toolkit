import { Text, Tooltip } from '@mantine/core'
import { useEffect, useState } from 'react'
import { api, type RegistryNode } from '../api'
import type { StatusPayload } from '../types'

/**
 * leafAgentVersion — the per-node agent binary that answered this status.
 *
 * A node keeps running the agent it was provisioned with, so after a toolkit
 * update the leaf can be older than the tip for days. Every fix we ship to the
 * leaf (disk layout, preflight, lifecycle) is invisible until this number moves.
 */
export function leafAgentVersion(st?: StatusPayload | null): string {
  if (!st) return ''
  const raw =
    st.agent_version ||
    st.agent?.version ||
    st.api_agent?.version ||
    st.version?.agent ||
    st.version?.toolkit ||
    ''
  return String(raw).trim()
}

/**
 * leafAgentBuild — which build of that version answered.
 *
 * Every local rebuild keeps chainAgentVersion, so the number alone cannot say
 * whether the binary on the host has the fix from ten minutes ago. The build id
 * (`<sha>[-dirty] <HH:MM>`) can.
 */
export function leafAgentBuild(st?: StatusPayload | null): string {
  return String(st?.agent_build || '').trim()
}

type InlineProps = {
  status?: StatusPayload | null
  /** Tip agent version of the host this node runs on (servers.agent_version). */
  tipVersion?: string
  /** Caller already labels the value (label/value list). */
  hideLabel?: boolean
}

/**
 * NodeAgentVersion — "agent 0.4.302" for the node header, orange when the leaf
 * is not the version the tip is running: that gap is the usual reason a fix
 * "did not work" after an update.
 */
export function NodeAgentVersion({ status, tipVersion, hideLabel }: InlineProps) {
  const ver = leafAgentVersion(status)
  const build = leafAgentBuild(status)
  const tip = String(tipVersion || '').trim()
  const stale = !!ver && !!tip && ver !== tip
  const title = !ver
    ? 'Per-node agent version unknown — the leaf agent has not answered yet'
    : stale
      ? `Node agent ${ver} · host tip runs ${tip}. Re-provision this node to write the new leaf binary.`
      : `Per-node agent (rpcnode-api-agent-<network>-<env>)${tip ? ` · same as the host tip` : ''}${
          build ? ` · build ${build}` : ''
        }`
  return (
    <>
      {hideLabel ? null : 'agent '}
      <Tooltip label={title} multiline w={320}>
        <Text span fw={600} className="mono" c={!ver ? 'gray.5' : stale ? 'orange.4' : 'gray.3'}>
          {ver || '—'}
          {stale ? ` (tip ${tip})` : ''}
        </Text>
      </Tooltip>
      {build ? (
        <Text span size="xs" c="dimmed" className="mono">
          {' '}
          +{build}
        </Text>
      ) : null}
    </>
  )
}

type TipState = {
  versions: string[]
  outdated: boolean
  latest: string
  hosts: string[]
  /** Build id when every host runs the same one — the version alone cannot say. */
  build: string
}

function summarize(items: RegistryNode[], latest: string): TipState {
  const versions: string[] = []
  const builds: string[] = []
  const hosts: string[] = []
  let outdated = false
  for (const s of items) {
    const v = String(s.agent_version || '').trim()
    const b = String(s.agent_build || '').trim()
    hosts.push(`${s.name || s.id || 'server'} — ${v || 'unknown'}${b ? ` (${b})` : ''}`)
    if (v && !versions.includes(v)) versions.push(v)
    if (b && !builds.includes(b)) builds.push(b)
    if (s.agent_update_available) outdated = true
  }
  // One build across the fleet is a fact worth printing; several is what the
  // version count already says, and the tooltip lists them per host.
  const build = builds.length === 1 ? builds[0] : ''
  return { versions, outdated, latest, hosts, build }
}

/**
 * TipAgentVersion — host agent version next to the panel version in the footer.
 *
 * Panel and agents ship separately, so "which agent is actually installed" is a
 * question the operator asks on every bug report. One host = that number; more
 * hosts running different builds = the count, with the hosts in the tooltip.
 */
export function TipAgentVersion() {
  const [state, setState] = useState<TipState | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      try {
        const res = await api.registryList()
        if (cancelled) return
        const items = res.items || []
        setState(items.length ? summarize(items, String(res.latest_agent_version || '')) : null)
      } catch {
        // The footer must stay quiet: the pages already report a dead panel API.
      }
    }
    void load()
    const t = setInterval(load, 60_000)
    return () => {
      cancelled = true
      clearInterval(t)
    }
  }, [])

  if (!state || state.versions.length === 0) return null
  const mixed = state.versions.length > 1
  const label = mixed ? `agents ${state.versions.length} versions` : `agent ${state.versions[0]}`
  const title = mixed
    ? state.hosts.join(' · ')
    : state.outdated && state.latest
      ? `Host agent ${state.versions[0]} · channel has ${state.latest} — update from Servers`
      : `Host tip agent on ${state.hosts.length} server(s)`
  return (
    <>
      {' · '}
      <Tooltip label={title} multiline w={340}>
        <Text span size="sm" c={state.outdated || mixed ? 'orange.4' : 'dimmed'}>
          {label}
          {state.build ? `+${state.build}` : ''}
          {state.outdated && state.latest && !mixed ? ` → ${state.latest}` : ''}
        </Text>
      </Tooltip>
    </>
  )
}
