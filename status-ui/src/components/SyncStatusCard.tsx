import { Badge, Card, Group, Progress, Text, Title } from '@mantine/core'
import { IconServer } from '@tabler/icons-react'
import type { StatusPayload, SyncInfo } from '../types'
import { CopyMaskedUrl } from './CopyMaskedUrl'
import { formatNodeWhen, formatSyncPct, num } from '../lib/format'
import { maskHostname } from '../lib/maskHost'
import {
  isBitcoinRegtestEnv,
  isBitcoinStatus,
  isNoSnapshotNetwork,
  isSolanaNetwork,
  isStellarNetwork,
  isTonNetwork,
  isTronNetwork,
  isXrplNetwork,
  resolveEnv,
  resolveNetwork,
  supportsIbdStep,
} from '../lib/network'
import { statusHonestlySynced } from '../lib/nodeLifecycle'
import {
  parseXrplComplete,
  xrplGenesisForEnv,
  xrplHistoryPct,
  xrplTipLive,
  xrplWindowPct,
} from '../lib/xrplSync'

/** lifecycle.pct or run-step pct (older agents often only set steps[].pct). */
function resolveLifecyclePct(status?: StatusPayload | null): number | null {
  const top = status?.lifecycle?.pct
  if (typeof top === 'number' && !Number.isNaN(top)) return top
  const steps = status?.lifecycle?.steps
  if (!Array.isArray(steps)) return null
  const run = steps.find((s) => (s?.id || '').toLowerCase() === 'run')
  const p = run?.pct
  if (typeof p === 'number' && !Number.isNaN(p)) return p
  if (p != null && p !== '') {
    const n = parseFloat(String(p).replace('%', ''))
    if (!Number.isNaN(n)) return n
  }

  return null
}

/** Already 0–100 (verification_pct / lifecycle.pct). Never ×100 — Solana 0.1% stays 0.1. */
function asPct100(raw: number | string | null | undefined): number | null {
  if (raw == null || raw === '') return null
  const n = typeof raw === 'number' ? raw : parseFloat(String(raw).replace('%', ''))
  if (Number.isNaN(n)) return null
  return Math.max(0, Math.min(100, n))
}

/** Bitcoin-style fraction 0–1 → 0–100. */
function asFractionToPct(raw: number | string | null | undefined): number | null {
  if (raw == null || raw === '') return null
  const n = typeof raw === 'number' ? raw : parseFloat(String(raw).replace('%', ''))
  if (Number.isNaN(n)) return null
  const pct = n > 0 && n <= 1.5 ? n * 100 : n
  return Math.max(0, Math.min(100, pct))
}

/** Agent sync % (0–100), or null when absent. */
export function resolveSyncProgressPct(status?: StatusPayload | null): number | null {
  const sync = resolveSyncInfo(status)
  // Snapshot download (TRON / Robinhood nitro --init.url) — real % from agent logs.
  const snapPct = asPct100(status?.snapshot?.pct ?? status?.snapshot?.progress_pct)
  const phase = (status?.ui_phase || status?.lifecycle?.phase || '').toLowerCase()
  const cur = (status?.lifecycle?.current || status?.lifecycle?.current_step_id || '').toLowerCase()
  const snapBusy =
    !!status?.snapshot?.wget_running ||
    phase === 'snapshot' ||
    cur === 'snapshot' ||
    (status?.node_status || '').toLowerCase().includes('snapshot')
  if (snapBusy && snapPct != null) return snapPct
  // XRPL: always compute from complete_ledgers — agent used to floor <0.1% to 0.1.
  if (isXrplNetwork(resolveNetwork(status))) {
    const win = parseXrplComplete(sync?.complete_ledgers) || parseXrplComplete(sync?.detail)
    const seq =
      (typeof sync?.ledger_seq === 'number' && sync.ledger_seq > 0
        ? sync.ledger_seq
        : null) ??
      (typeof sync?.headers === 'number' && sync.headers > 0 ? sync.headers : null) ??
      (typeof sync?.blocks === 'number' && sync.blocks > 0 ? sync.blocks : null)
    if (win && seq) {
      const target = typeof sync?.history_ledgers === 'number' ? sync.history_ledgers : 0
      if (target > 0) {
        return xrplWindowPct(win.lo, win.hi, target)
      }

      return xrplHistoryPct(win.lo, seq, xrplGenesisForEnv(resolveEnv(status)))
    }
  }
  // verification_pct is always 0–100 (solana lag-closed, eth, …). ❌ do not treat 0.1 as 10%.
  // While syncing, 0 often means “signal missing” (base-reth 0x0/0x0) — fall through
  // to lifecycle / dump before painting an empty bar.
  const fromPct = asPct100(sync?.verification_pct)
  const syncingNow = !!(sync?.ibd || sync?.syncing)
  if (fromPct != null && !(syncingNow && fromPct === 0)) return fromPct
  if (snapPct != null) return snapPct
  // TON dump phase — agent may emit dump_pct before lag-closed verification_pct.
  const dumpPct = asPct100(sync?.dump_pct)
  if (dumpPct != null && dumpPct > 0) return dumpPct
  // verificationprogress / verify_pct — legacy 0–1 (bitcoind).
  const fromFrac =
    asFractionToPct(sync?.verificationprogress) ?? asFractionToPct(sync?.verify_pct)
  if (fromFrac != null && !(syncingNow && fromFrac === 0)) return fromFrac
  const life = asPct100(resolveLifecyclePct(status))
  if (life != null && !(syncingNow && life === 0)) return life
  if (fromPct != null) return fromPct
  if (fromFrac != null) return fromFrac
  return life
}

/** Whether to show sync status / progress on the node page (every network). */
export function showSyncStatusCard(
  status?: StatusPayload | null,
  networkHint?: string | null,
): boolean {
  if (!status) return false
  // Regtest: no IBD card — local tip is not network sync.
  if (isBitcoinRegtestEnv(resolveEnv(status))) return false

  const sync = resolveSyncInfo(status)
  const phase = (status.ui_phase || status.lifecycle?.phase || '').toLowerCase()
  const ns = (status.node_status || status.lifecycle?.node_status || '').toLowerCase()
  const cur = (status.lifecycle?.current || status.lifecycle?.current_step_id || '').toLowerCase()
  const net = resolveNetwork(status, networkHint)

  // Always show when agent reported a sync % (including 100% / Synced).
  if (resolveSyncProgressPct(status) != null) return true
  if (sync?.ibd || sync?.syncing || sync?.ok === false) return true
  if (ns === 'syncing' || phase === 'run' || cur === 'run' || cur === 'ibd' || ns === 'starting') {
    return true
  }
  if ((sync?.log_tail?.length || 0) > 0 && !status.connect?.ready) return true
  // Sync-capable profiles (HL / BTC / eth / …) even before first pct sample.
  if (supportsIbdStep(status, networkHint) || isBitcoinStatus(status, networkHint)) return true
  // No-snapshot chains (HL, doge, cardano, …): show bar whenever we have status.
  if (isNoSnapshotNetwork(net)) return true

  return false
}

export function resolveSyncInfo(status?: StatusPayload | null): SyncInfo | null {
  if (!status) return null
  const regtest = isBitcoinRegtestEnv(resolveEnv(status))
  if (status.sync && typeof status.sync === 'object') {
    const raw = status.sync as SyncInfo & {
      catching?: boolean
      slot?: number
      height?: number
      size_on_disk_gb?: number
    }
    const catching = !regtest && !!(raw.catching || raw.syncing || raw.ibd)
    let sync: SyncInfo = regtest
      ? { ...raw, ibd: false, syncing: false }
      : { ...raw, ibd: catching || !!raw.ibd, syncing: catching || !!raw.syncing }
    // Prefer sync.*; fill gaps from rpc when agent omitted fields on sync.
    const rpc = status.rpc || {}
    if (typeof sync.verification_pct !== 'number' && typeof rpc.verification_pct === 'number') {
      sync = { ...sync, verification_pct: rpc.verification_pct }
    }
    if (!sync.complete_ledgers && typeof rpc.complete_ledgers === 'string') {
      sync = { ...sync, complete_ledgers: rpc.complete_ledgers }
    }
    if (!sync.server_state && typeof rpc.server_state === 'string') {
      sync = { ...sync, server_state: rpc.server_state }
    }
    if (sync.ledger_seq == null && typeof rpc.ledger_seq === 'number') {
      sync = { ...sync, ledger_seq: rpc.ledger_seq }
    }
    if (sync.blocks == null && typeof raw.slot === 'number') {
      sync = { ...sync, blocks: raw.slot, headers: sync.headers ?? raw.slot }
    }
    if (sync.blocks == null && typeof raw.height === 'number') {
      sync = { ...sync, blocks: raw.height, headers: sync.headers ?? raw.height }
    }
    if (sync.blocks == null && typeof rpc.node_height === 'number') {
      sync = { ...sync, blocks: rpc.node_height }
    }
    if (sync.blocks_behind == null && typeof raw.blocks_behind === 'number') {
      sync = { ...sync, blocks_behind: raw.blocks_behind }
    }
    if (sync.blocks_behind == null && typeof raw.behind === 'number') {
      sync = { ...sync, blocks_behind: raw.behind }
    }
    if (sync.blocks == null && typeof rpc.slot === 'number') {
      sync = { ...sync, blocks: rpc.slot, headers: sync.headers ?? rpc.slot }
    }
    if (sync.peers == null && typeof rpc.peers === 'number') {
      sync = { ...sync, peers: rpc.peers }
    }
    if (sync.size_on_disk == null && typeof rpc.size_on_disk === 'number') {
      sync = { ...sync, size_on_disk: rpc.size_on_disk }
    }
    if (sync.size_on_disk_gb == null && typeof raw.size_on_disk_gb === 'number') {
      sync = { ...sync, size_on_disk_gb: raw.size_on_disk_gb }
    }
    if (sync.tip_ledger == null && typeof raw.tip_ledger === 'number') {
      sync = { ...sync, tip_ledger: raw.tip_ledger }
    }
    if (sync.block_height == null && typeof rpc.block_height === 'number') {
      sync = { ...sync, block_height: rpc.block_height }
    }
    if (sync.slot == null && typeof rpc.slot === 'number') {
      sync = { ...sync, slot: rpc.slot }
    }
    if (
      sync.out_of_sync_sec == null &&
      typeof (rpc as { out_of_sync_sec?: number }).out_of_sync_sec === 'number'
    ) {
      sync = {
        ...sync,
        out_of_sync_sec: (rpc as { out_of_sync_sec?: number }).out_of_sync_sec,
      }
    }
    if (typeof sync.verification_pct !== 'number') {
      const lp = resolveLifecyclePct(status)
      if (lp != null) sync = { ...sync, verification_pct: lp }
    }

    return sync
  }
  const rpc = status.rpc || {}
  const hasSyncRPC =
    typeof rpc.blocks === 'number' ||
    typeof rpc.block === 'number' ||
    typeof rpc.headers === 'number' ||
    typeof rpc.slot === 'number' ||
    typeof rpc.height === 'number' ||
    typeof rpc.node_height === 'number' ||
    typeof rpc.initialblockdownload === 'boolean' ||
    typeof rpc.syncing === 'boolean' ||
    typeof rpc.verificationprogress === 'number' ||
    typeof rpc.verification_pct === 'number'
  if (!hasSyncRPC) {
    // Still show card metrics from lifecycle run pct (Solana synced on older agents).
    const lp = resolveLifecyclePct(status)
    if (lp == null) return null

    return {
      ok: true,
      verification_pct: lp,
      detail: status.lifecycle?.detail,
      updated_at: status.updated_at,
    }
  }
  const verify =
    typeof rpc.verification_pct === 'number'
      ? rpc.verification_pct
      : typeof rpc.verificationprogress === 'number'
        ? rpc.verificationprogress * 100
        : (resolveLifecyclePct(status) ?? undefined)
  const slotOrHeight =
    typeof rpc.slot === 'number'
      ? rpc.slot
      : typeof rpc.height === 'number'
        ? rpc.height
        : typeof rpc.node_height === 'number'
          ? rpc.node_height
          : undefined

  return {
    ibd: regtest ? false : !!(rpc.initialblockdownload || rpc.syncing),
    syncing: regtest ? false : !!(rpc.syncing || rpc.initialblockdownload),
    blocks: typeof rpc.blocks === 'number' ? rpc.blocks : slotOrHeight,
    block: typeof rpc.block === 'number' ? rpc.block : undefined,
    headers: typeof rpc.headers === 'number' ? rpc.headers : undefined,
    verificationprogress:
      typeof rpc.verificationprogress === 'number' ? rpc.verificationprogress : undefined,
    verification_pct: verify,
    size_on_disk: typeof rpc.size_on_disk === 'number' ? rpc.size_on_disk : undefined,
    peers: typeof rpc.peers === 'number' ? rpc.peers : undefined,
    ok: rpc.ok ?? rpc.reachable ?? rpc.http_ok,
    updated_at: status.updated_at,
    detail: status.lifecycle?.detail,
  }
}

/** Compact live IBD status + host Server strip (agent data only). */
export function SyncStatusCard({
  status,
  network,
  serverLabel,
  serverURL,
  serverOs,
}: {
  status: StatusPayload
  network?: string | null
  serverLabel?: string | null
  serverURL?: string | null
  serverOs?: string | null
}) {
  const sync = resolveSyncInfo(status)
  const hasSync = !!(sync || resolveSyncProgressPct(status) != null)
  const hasServer = !!(serverLabel || serverURL)
  if (!hasSync && !hasServer) return null

  const progress = resolveSyncProgressPct(status)
  const regtest = isBitcoinRegtestEnv(resolveEnv(status))
  const syncing = !regtest && !!(sync?.ibd || sync?.syncing)
  const honestlySynced = !regtest && statusHonestlySynced(status)
  // Never paint «Synced» while lifecycle incomplete unless honestly synced
  // (panel heal should complete; this gate kills dual-state if heal lags).
  const showSyncedBadge = honestlySynced
  const detail =
    sync?.detail ||
    status.lifecycle?.detail ||
    (regtest
      ? `Regtest · blocks ${sync?.blocks ?? 0}`
      : syncing
        ? 'Syncing'
        : 'Sync status')
  const updated = sync?.block_time || sync?.updated_at || status.updated_at || status.served_at
  const blocks = sync?.blocks ?? sync?.block ?? sync?.height
  // Agent/DB verification_pct only — never invent a UI floor (0.1% stays 0.1%).
  const barValue = progress != null ? progress : 0
  const net = resolveNetwork(status, network)
  const solana = isSolanaNetwork(net)
  const stellar = isStellarNetwork(net)
  const ton = isTonNetwork(net)
  const tron = isTronNetwork(net)
  const xrpl = isXrplNetwork(net)
  const xrplWin = xrpl
    ? parseXrplComplete(sync?.complete_ledgers) || parseXrplComplete(sync?.detail)
    : null
  const xrplSeq =
    (typeof sync?.ledger_seq === 'number' && sync.ledger_seq > 0
      ? sync.ledger_seq
      : null) ??
    (typeof sync?.headers === 'number' && sync.headers > 0 ? sync.headers : null) ??
    (typeof sync?.blocks === 'number' && sync.blocks > 0 && !xrplWin ? sync.blocks : null)
  const xrplTipOk = xrpl && xrplTipLive(sync?.server_state, sync?.detail)
  const xrplTarget =
    xrpl && typeof sync?.history_ledgers === 'number' ? sync.history_ledgers : 0
  const xrplHistPct =
    xrpl && xrplWin && xrplSeq
      ? xrplTarget > 0
        ? xrplWindowPct(xrplWin.lo, xrplWin.hi, xrplTarget)
        : xrplHistoryPct(xrplWin.lo, xrplSeq, xrplGenesisForEnv(resolveEnv(status)))
      : xrpl
        ? progress
        : null
  const xrplStored =
    xrplWin && xrplSeq ? Math.max(0, xrplWin.hi - xrplWin.lo + 1) : null
  const tronBehind =
    typeof sync?.blocks_behind === 'number'
      ? sync.blocks_behind
      : typeof sync?.behind === 'number'
        ? sync.behind
        : null
  const solanaSlot = sync?.slot ?? blocks
  const solanaBlockHeight = sync?.block_height
  const tipHeaders =
    typeof sync?.headers === 'number' && sync.headers > 0 ? sync.headers : null
  const tipLedger =
    typeof sync?.tip_ledger === 'number' && sync.tip_ledger > 0
      ? sync.tip_ledger
      : tipHeaders
  const solanaTip =
    typeof sync?.cluster_slot === 'number' && sync.cluster_slot > 0
      ? sync.cluster_slot
      : tipHeaders
  const heightLabel = solana || tron
    ? 'node / tip'
    : xrpl
      ? 'complete / tip'
      : stellar
        ? 'ledger / tip'
        : ton
          ? 'seqno'
          : 'blocks / headers'
  const heightValue = xrpl
    ? xrplWin && xrplSeq
      ? `${num(xrplWin.lo, 0)}–${num(xrplWin.hi, 0)} / ${num(xrplSeq, 0)}`
      : xrplSeq != null
        ? `${num(xrplSeq, 0)} / —`
        : '—'
    : solana
    ? solanaSlot != null
      ? solanaTip != null
        ? `${num(solanaSlot, 0)} / ${num(solanaTip, 0)}`
        : num(solanaSlot, 0)
      : '—'
    : ton
      ? blocks != null
        ? num(blocks, 0)
        : '—'
      : blocks != null
        ? tipLedger != null || tipHeaders != null
          ? `${num(blocks, 0)} / ${num(tipLedger ?? tipHeaders ?? 0, 0)}`
          : !syncing
            ? `${num(blocks, 0)} / ${num(blocks, 0)}`
            : `${num(blocks, 0)} / —`
        : '—'

  const kv: { k: string; v: string }[] = []
  if (hasSync) {
    kv.push({ k: heightLabel, v: heightValue })
    if (xrpl && xrplStored != null) {
      kv.push({ k: 'stored', v: num(xrplStored, 0) })
    }
    if (solana) {
      if (solanaSlot != null) {
        kv.push({ k: 'node slot', v: num(solanaSlot, 0) })
      }
      if (solanaTip != null) {
        kv.push({ k: 'network tip', v: num(solanaTip, 0) })
      }
      if (typeof sync?.slots_behind === 'number' && sync.slots_behind > 0) {
        kv.push({ k: 'behind', v: num(sync.slots_behind, 0) })
      }
      kv.push({
        k: 'block height',
        v: solanaBlockHeight != null ? num(solanaBlockHeight, 0) : 'n/a',
      })
    }
    if (ton && typeof sync?.out_of_sync_sec === 'number') {
      kv.push({ k: 'behind', v: `${num(sync.out_of_sync_sec, 1)} sec` })
    }
    if (tron && tronBehind != null && tronBehind > 0) {
      kv.push({ k: 'behind', v: num(tronBehind, 0) })
    }
    // Stellar/TON have no bitcoin-style peer count — omit empty peers row.
    if (!stellar && !ton) {
      kv.push({
        k: 'peers',
        v: sync?.peers != null && Number(sync.peers) >= 0 ? String(sync.peers) : 'n/a',
      })
    }
    kv.push({
      k: 'disk',
      v:
        sync?.size_on_disk_gb != null
          ? `${num(sync.size_on_disk_gb, 1)} GiB`
          : sync?.size_on_disk != null
            ? `${num(Number(sync.size_on_disk) / (1024 * 1024 * 1024), 1)} GiB`
            : 'n/a',
    })
    kv.push({ k: 'updated', v: updated ? formatNodeWhen(updated) : '—' })
  }

  return (
    <Card padding="md">
      <Group justify="space-between" mb={6} wrap="nowrap" gap="xs">
        <Title order={4} c="dimmed" tt="uppercase" size="xs">
          Sync status
        </Title>
        {hasSync ? (
          <Badge
            size="sm"
            color={
              syncing
                ? 'yellow'
                : sync?.ok === false
                  ? 'red'
                  : showSyncedBadge || regtest
                    ? 'teal'
                    : 'gray'
            }
            variant="light"
          >
            {syncing
              ? 'Syncing'
              : sync?.ok === false
                ? status?.rpc?.ok || status?.rpc?.reachable || status?.rpc?.http_ok
                  ? 'Waiting'
                  : 'RPC down'
                : regtest
                  ? 'Regtest'
                  : showSyncedBadge
                    ? 'Synced'
                    : 'Catching up'}
          </Badge>
        ) : null}
      </Group>

      {hasServer ? (
        <Group gap={6} wrap="nowrap" mb={hasSync ? 8 : 0} align="center">
          <IconServer size={14} style={{ opacity: 0.65, flexShrink: 0 }} />
          <Text size="xs" fw={600} lineClamp={1} style={{ minWidth: 0 }} title={serverLabel || undefined}>
            {serverLabel
              ? /^\d{1,3}(\.\d{1,3}){3}$/.test(serverLabel.trim())
                ? maskHostname(serverLabel.trim())
                : serverLabel
              : 'Server'}
          </Text>
          {serverOs ? (
            <Badge size="xs" color="gray" variant="light" style={{ flexShrink: 0 }}>
              {serverOs}
            </Badge>
          ) : null}
          {serverURL ? (
            <div style={{ flex: 1, minWidth: 0 }}>
              <CopyMaskedUrl url={serverURL} compact copyMessage="Agent URL copied" />
            </div>
          ) : null}
        </Group>
      ) : null}

      {hasSync ? (
        <>
          <Group align="flex-end" gap="sm" mb={4} wrap="nowrap">
            <Text fw={750} size="xl" style={{ letterSpacing: '-0.03em', lineHeight: 1.1 }}>
              {solana &&
              typeof sync?.slots_behind === 'number' &&
              sync.slots_behind > 0 &&
              syncing ? (
                <>
                  {Number(sync.slots_behind).toLocaleString()}
                  <Text span size="sm" c="dimmed" fw={600} ml={6}>
                    behind
                  </Text>
                  {progress != null ? (
                    <Text span size="sm" c="dimmed" fw={600} ml={8}>
                      · {progress.toFixed(1)}% lag closed
                    </Text>
                  ) : null}
                </>
              ) : tron &&
                tronBehind != null &&
                tronBehind > 0 &&
                syncing ? (
                <>
                  {Number(tronBehind).toLocaleString()}
                  <Text span size="sm" c="dimmed" fw={600} ml={6}>
                    behind
                  </Text>
                  {progress != null ? (
                    <Text span size="sm" c="dimmed" fw={600} ml={8}>
                      · {progress.toFixed(1)}% lag closed
                    </Text>
                  ) : null}
                </>
              ) : ton &&
                typeof sync?.out_of_sync_sec === 'number' &&
                syncing ? (
                <>
                  {Number(sync.out_of_sync_sec).toLocaleString()}
                  <Text span size="sm" c="dimmed" fw={600} ml={6}>
                    sec behind
                  </Text>
                  {progress != null ? (
                    <Text span size="sm" c="dimmed" fw={600} ml={8}>
                      · {progress.toFixed(1)}% lag closed
                    </Text>
                  ) : null}
                </>
              ) : ton &&
                typeof sync?.dump_pct === 'number' &&
                sync.dump_pct > 0 &&
                syncing &&
                typeof sync?.out_of_sync_sec !== 'number' ? (
                <>
                  {sync.dump_pct}%
                  <Text span size="sm" c="dimmed" fw={600} ml={6}>
                    dump
                  </Text>
                </>
              ) : xrpl && xrplHistPct != null ? (
                formatSyncPct(xrplHistPct)
              ) : progress != null ? (
                formatSyncPct(progress)
              ) : syncing ? (
                '…'
              ) : (
                '—'
              )}
            </Text>
            <Text c="dimmed" size="xs" lineClamp={2} style={{ flex: 1, minWidth: 0 }}>
              {xrpl
                ? xrplWin && xrplSeq
                  ? `${xrplTipOk ? 'Live tip' : 'Catching tip'} · history ${num(xrplStored ?? 0, 0)} ledgers · ${xrplWin.lo}–${xrplWin.hi}`
                  : detail
                : solana && solanaSlot != null && solanaTip != null
                  ? `node ${Number(solanaSlot).toLocaleString()} · tip ${Number(solanaTip).toLocaleString()}`
                  : detail}
            </Text>
          </Group>
          {xrpl ? (
            <>
              <Text size="xs" c="dimmed" mb={2}>
                Tip {xrplTipOk ? 'live' : sync?.server_state || '…'}
              </Text>
              <Progress
                value={xrplTipOk ? 100 : 0}
                color={xrplTipOk ? 'teal' : 'yellow'}
                size="sm"
                radius="xl"
                mb={6}
                animated={!xrplTipOk}
                striped={!xrplTipOk}
              />
              <Text size="xs" c="dimmed" mb={2}>
                {xrplTarget > 0
                  ? `History window · ${num(xrplTarget, 0)} ledgers`
                  : 'History toward genesis'}
                {xrplHistPct != null ? ` · ${formatSyncPct(xrplHistPct)}` : ''}
              </Text>
              <Progress
                value={xrplHistPct ?? 0}
                color={xrplHistPct != null && xrplHistPct >= 99.9 ? 'teal' : 'yellow'}
                size="md"
                radius="xl"
                mb={8}
                animated={xrplHistPct == null || xrplHistPct < 99.9}
                striped={xrplHistPct == null || xrplHistPct < 99.9}
              />
            </>
          ) : (
          <Progress
            value={barValue}
            color={syncing ? 'yellow' : sync?.ok === false ? 'red' : 'teal'}
            size="sm"
            radius="xl"
            mb={8}
            animated={syncing || (progress != null && progress < 100)}
            striped={syncing || (progress != null && progress < 100)}
          />
          )}

          <Group gap={6} wrap="wrap">
            {kv.map((row) => (
              <Badge
                key={row.k}
                size="sm"
                variant="light"
                color="gray"
                radius="sm"
                tt="none"
                className="mono"
                title={`${row.k}: ${row.v}`}
                styles={{
                  root: { textTransform: 'none', fontWeight: 500, maxWidth: '100%' },
                  label: { overflow: 'hidden', textOverflow: 'ellipsis' },
                }}
              >
                <Text span size="xs" c="dimmed" mr={4}>
                  {row.k}
                </Text>
                {row.v}
              </Badge>
            ))}
          </Group>
        </>
      ) : null}
    </Card>
  )
}
