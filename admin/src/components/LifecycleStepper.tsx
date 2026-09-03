import { Badge, Group, Progress, Skeleton, Stack, Text, ThemeIcon } from '@mantine/core'
import { IconCheck, IconCircleDot, IconAlertTriangle } from '@tabler/icons-react'
import {
  deriveLifecycleSteps,
  filterLifecycleStepsForNetwork,
  resolveCurrentStep,
  snapshotDownloadLive,
  splitStepHeadline,
} from '../lib/nodeLifecycle'
import { resolveEnv, supportsSnapshotStep } from '../lib/network'
import type { LifecycleInfo, LifecycleStep, StatusPayload } from '../types'
import {
  formatSyncPct,
  parseAria2PctFromText,
  parseSolanaDownloadPctFromText,
  parseSyncPctFromDetail,
  pct,
} from '../lib/format'
import { blockProps } from '../lib/blockId'

type Props = {
  status?: StatusPayload | null
  lifecycle?: LifecycleInfo | null
  /** Compact single-row for Nodes cards */
  compact?: boolean
  workloadStatus?: string | null
  /** Workload / route network hint (bitcoin → hide snapshot until supported_steps). */
  network?: string | null
  /** Route / workload env (tron/shasta → no Snapshot ExtraStep). */
  env?: string | null
  /** From panel `GET /api/nodes/{id}` → needs_snapshot (catalog snapshot: required). */
  needsSnapshot?: boolean
  /** Hide agent `ports` step (e.g. add-node contexts). Default: show all agent steps. */
  hidePortsStep?: boolean
  /**
   * False until panel workload is loaded.
   * When false, show skeleton — never flash step 0 / Install.
   */
  ready?: boolean
}

function stepColor(s: LifecycleStep): string {
  if (s.error || s.status === 'error') return 'red'
  if (s.status === 'skipped') return 'gray'
  if (s.done || s.status === 'done') return 'teal'
  if (s.active || s.status === 'active') return 'yellow'
  return 'dark'
}

/** Bar value = agent/DB pct only. No UI floor (Solana 0.1% must stay 0.1). */
function syncBarFill(progress: number, _syncing?: boolean): number {
  if (progress > 0) return Math.min(100, progress)
  return 0
}

function synthesizeSteps(
  phase?: string,
  detail?: string,
  allowSnapshot?: boolean,
): LifecycleStep[] {
  const order = (
    allowSnapshot
      ? (['install', 'snapshot', 'start', 'run'] as const)
      : (['install', 'start', 'run'] as const)
  )
  const titles: Record<string, string> = {
    install: 'Install',
    snapshot: 'Snapshot',
    start: 'Start',
    run: 'Run',
  }
  const idxMap: Record<string, number> = allowSnapshot
    ? {
        setup: 0,
        install: 0,
        installing: 1,
        snapshot: 1,
        starting: 2,
        start: 2,
        syncing: 3,
        run: 3,
        working: 4,
        healthy: 4,
        error: 1,
      }
    : {
        setup: 0,
        install: 0,
        installing: 0,
        snapshot: 1,
        starting: 1,
        start: 1,
        syncing: 2,
        run: 2,
        ibd: 2,
        working: 3,
        healthy: 3,
        error: 1,
      }
  const key = (phase || '').toLowerCase()
  // Unknown / empty phase → no active step (avoid Install flash).
  if (!(key in idxMap)) {
    return order.map((id) => ({
      id,
      title: titles[id],
      status: 'pending' as const,
      done: false,
      active: false,
      error: false,
      detail: '',
    }))
  }
  const idx = idxMap[key]
  const err = key === 'error'
  const doneAt = order.length
  return order.map((id, i) => {
    let statusName: LifecycleStep['status'] = 'pending'
    if (err && i === idx) statusName = 'error'
    else if (i < idx) statusName = 'done'
    else if (i === idx && idx < doneAt) statusName = 'active'
    else if (idx >= doneAt) statusName = 'done'
    return {
      id,
      title: titles[id],
      status: statusName,
      done: statusName === 'done',
      active: statusName === 'active',
      error: statusName === 'error',
      detail: statusName === 'active' || statusName === 'error' ? detail || '' : '',
    }
  })
}

function StepperSkeleton({ compact }: { compact?: boolean }) {
  if (compact) {
    return (
      <Stack gap={6}>
        <Group gap={6} wrap="wrap">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} height={18} width={18} radius="xl" />
          ))}
          <Skeleton height={16} width={96} radius="sm" />
        </Group>
      </Stack>
    )
  }

  return (
    <Stack gap="sm" className="lifecycle-stepper lifecycle-stepper--loading">
      <Group justify="space-between" align="flex-start" className="lifecycle-stepper__head">
        <div style={{ flex: 1 }}>
          <Text size="xs" className="lifecycle-stepper__kicker">
            lifecycle
          </Text>
          <Skeleton height={18} width={180} mt={6} />
          <Skeleton height={12} width={240} mt={8} />
        </div>
        <Skeleton height={22} width={48} radius={0} />
      </Group>
      <div className="lifecycle-stepper__steps">
        {[0, 1, 2, 3].map((i) => (
          <Group key={i} gap={8} wrap="nowrap" align="flex-start" className="lifecycle-stepper__step">
            <Skeleton height={24} width={24} radius={0} style={{ flexShrink: 0 }} />
            <div className="lifecycle-stepper__step-body">
              <Skeleton height={14} width="70%" mb={6} />
              <Skeleton height={10} width="90%" />
            </div>
          </Group>
        ))}
      </div>
    </Stack>
  )
}

export function LifecycleStepper({
  status,
  lifecycle,
  compact,
  workloadStatus,
  network,
  env,
  needsSnapshot,
  hidePortsStep,
  ready,
}: Props) {
  const lc = lifecycle || status?.lifecycle || null
  const allowSnap = supportsSnapshotStep(status, network, env ?? resolveEnv(status), needsSnapshot)
  const hasAgentSteps = !!(lc?.steps && lc.steps.length > 0)
  const hasAgentCursor = !!(
    lc?.current ||
    lc?.current_step_id ||
    lc?.current_step?.id ||
    lc?.current_index != null ||
    lc?.phase
  )

  // Until panel workload is ready — never paint Install/step 0 as current.
  if (ready === false) {
    return <StepperSkeleton compact={compact} />
  }

  const dataReady = status != null || hasAgentSteps || hasAgentCursor
  if (!dataReady) {
    // Poll finished but no payload yet (unreachable agent) — do not invent Install.
    if (ready === true) {
      return compact ? null : (
        <div {...blockProps('node.detail.lifecycle-stepper')}>
          <Text size="sm" c="dimmed" className="lifecycle-stepper__wait">
            Waiting for agent status…
          </Text>
        </div>
      )
    }
    return <StepperSkeleton compact={compact} />
  }

  let steps = (hasAgentSteps
    ? filterLifecycleStepsForNetwork(lc!.steps!, status, network, needsSnapshot)
    : status
      ? deriveLifecycleSteps(status, workloadStatus, network, needsSnapshot)
      : []) as LifecycleStep[]
  if (!steps.length && hasAgentCursor) {
    steps = synthesizeSteps(lc?.phase || lifecycle?.phase, lc?.detail || lifecycle?.detail, allowSnap)
  }
  if (hidePortsStep) {
    steps = steps.filter((s) => (s.id || '').toLowerCase() !== 'ports')
  }

  if (!steps.length) {
    return <StepperSkeleton compact={compact} />
  }

  const current = resolveCurrentStep(lc, steps)
  // Without a resolved current step, keep skeleton rather than implying step 1.
  if (!current && !hasAgentSteps && !hasAgentCursor) {
    return <StepperSkeleton compact={compact} />
  }

  // Agent fields only — do not rewrite titles/details per network in the UI.
  const displayCurrent = current

  const active =
    steps.find((s) => s.active || s.status === 'active') ||
    (displayCurrent ? steps.find((s) => s.id === displayCurrent.id) : undefined)
  const snapPhase = (status?.snapshot?.phase || '').toLowerCase()
  const snapAborted = snapPhase === 'aborted' || !!status?.snapshot?.aborted
  const snapLive =
    !snapAborted &&
    (snapshotDownloadLive(status) ||
    !!status?.snapshot?.wget_running ||
    !!status?.snapshot?.busy ||
    snapPhase === 'download' ||
    snapPhase === 'extract' ||
    snapPhase === 'extracting' ||
    (parseAria2PctFromText(String(status?.snapshot?.detail || '')) != null &&
      (status?.snapshot?.ready !== true || snapshotDownloadLive(status))))
  const showSnapProgress =
    (allowSnap || snapLive) &&
    (lc?.phase || '').toLowerCase() !== 'working' &&
    (lc?.node_status || '').toLowerCase() !== 'online' &&
    !lc?.complete &&
    (active?.id === 'snapshot' ||
      displayCurrent?.id === 'snapshot' ||
      lc?.phase === 'snapshot' ||
      snapLive)
  const curId = (displayCurrent?.id || active?.id || lc?.current || '').toLowerCase()
  const phaseLow = (lc?.phase || '').toLowerCase()
  const nodeStatusLow = (lc?.node_status || '').toLowerCase()
  const hasAgentPct =
    lc?.pct != null &&
    lc.pct !== '' &&
    !Number.isNaN(Number(typeof lc.pct === 'number' ? lc.pct : String(lc.pct).replace('%', '')))
  const heightVal = lc?.height ?? status?.rpc?.node_height ?? null
  // Collector maps IBD → phase "syncing" (not agent "run") — list cards pass that phase + pct.
  // TON dump/bootstrap stays on lifecycle phase "start" / DB "starting" with honest % —
  // still show the bar (was disappearing on /nodes during MyTonCtrl dump).
  const bootstrapBusy =
    phaseLow === 'start' ||
    phaseLow === 'starting' ||
    nodeStatusLow === 'starting' ||
    curId === 'start'
  const showSyncProgress =
    !showSnapProgress &&
    (curId === 'run' ||
      curId === 'ibd' ||
      phaseLow === 'run' ||
      phaseLow === 'syncing' ||
      phaseLow === 'working' ||
      nodeStatusLow === 'syncing' ||
      nodeStatusLow === 'ibd' ||
      nodeStatusLow === 'online' ||
      bootstrapBusy ||
      !!status?.sync?.ibd ||
      !!status?.sync?.syncing ||
      typeof status?.sync?.verification_pct === 'number' ||
      typeof status?.rpc?.verification_pct === 'number' ||
      typeof status?.sync?.dump_pct === 'number' ||
      hasAgentPct ||
      (heightVal != null &&
        (phaseLow === 'syncing' ||
          phaseLow === 'working' ||
          hasAgentPct ||
          Number(heightVal) > 0)))
  const progressRaw = showSnapProgress
    ? (status?.snapshot?.pct ??
        lc?.pct ??
        active?.pct ??
        displayCurrent?.pct ??
        status?.sync?.verification_pct ??
        status?.rpc?.verification_pct)
    : (lc?.pct ??
        active?.pct ??
        displayCurrent?.pct ??
        status?.sync?.verification_pct ??
        status?.rpc?.verification_pct ??
        status?.sync?.dump_pct ??
        status?.snapshot?.pct)
  let progress =
    progressRaw == null || progressRaw === ''
      ? 0
      : pct(typeof progressRaw === 'number' ? progressRaw : String(progressRaw))
  const syncingBar =
    showSyncProgress &&
    (curId === 'run' ||
      curId === 'ibd' ||
      phaseLow === 'run' ||
      phaseLow === 'syncing' ||
      nodeStatusLow === 'syncing' ||
      nodeStatusLow === 'ibd' ||
      bootstrapBusy ||
      !!status?.sync?.ibd ||
      !!status?.sync?.syncing) &&
    progress < 100
  const allDone =
    steps.length > 0 &&
    steps.every((s) => s.done || s.status === 'done' || s.status === 'skipped') &&
    (phaseLow === 'working' ||
      phaseLow === 'healthy' ||
      nodeStatusLow === 'online' ||
      !!lc?.complete)

  const label = allDone
    ? 'Synced'
    : displayCurrent?.countLabel ||
      (lc?.label ? splitStepHeadline(lc.label).count : '') ||
      active?.title ||
      status?.ui_phase ||
      'Loading…'
  const detail = displayCurrent?.detail || lc?.detail || active?.detail || ''
  if (progress <= 0) {
    const blob = [
      detail,
      status?.snapshot?.detail,
      status?.sync?.detail,
      ...(status?.sync?.log_tail || []),
      ...(status?.logs?.lines || []),
    ]
      .filter(Boolean)
      .join('\n')
    const fromDetail =
      parseAria2PctFromText(blob) ??
      parseSolanaDownloadPctFromText(blob) ??
      parseSyncPctFromDetail(detail) ??
      parseSyncPctFromDetail(status?.snapshot?.detail)
    if (fromDetail != null && fromDetail > 0) {
      progress = fromDetail
    }
  }
  // Head already shows the current step's detail — don't echo it under the title.
  const headDetail =
    detail &&
    !(displayCurrent?.detail && detail.trim() === String(displayCurrent.detail).trim())
      ? detail
      : ''

  const heightLabel =
    heightVal == null
      ? ''
      : network === 'solana'
        ? `slot ${Number(heightVal).toLocaleString()}`
        : `${Number(heightVal).toLocaleString()} blk`
  const syncRightLabel = heightLabel
    ? !allDone && progress > 0
      ? `${heightLabel} · ${formatSyncPct(progress)}`
      : heightLabel
    : progress > 0 || syncingBar
      ? progress > 0
        ? formatSyncPct(progress)
        : '…'
      : ''

  if (compact) {
    return (
      <div {...blockProps('node.detail.lifecycle-stepper')}>
      <Stack gap={6}>
        <Group gap={6} wrap="wrap">
          {!allDone &&
            steps.map((s) => (
              <ThemeIcon
                key={s.id || s.title}
                size={18}
                radius="xl"
                color={stepColor(s)}
                variant={s.active || s.status === 'active' ? 'filled' : 'light'}
                title={`${s.title}: ${s.status || (s.done ? 'done' : 'pending')}`}
              >
                {s.error || s.status === 'error' ? (
                  <IconAlertTriangle size={10} />
                ) : s.status === 'skipped' ? (
                  <IconCircleDot size={10} />
                ) : s.done || s.status === 'done' ? (
                  <IconCheck size={10} />
                ) : (
                  <IconCircleDot size={10} />
                )}
              </ThemeIcon>
            ))}
          <Badge
            size="xs"
            variant="light"
            color={allDone ? 'teal' : active || displayCurrent ? 'yellow' : 'gray'}
          >
            {label}
          </Badge>
        </Group>
        {showSnapProgress && (
          <Group gap={8} wrap="wrap" align="center" mt={4}>
            <Progress
              value={syncBarFill(
                progress > 0 ? progress : status?.snapshot?.wget_running || status?.snapshot?.busy ? 6 : 2,
                true,
              )}
              size="sm"
              style={{ flex: 1, minWidth: 0 }}
              animated
              striped
              color="yellow"
            />
            <Text size="xs" c="dimmed" className="mono" style={{ flexShrink: 0 }}>
              {progress > 0 ? formatSyncPct(progress) : '…'}
            </Text>
          </Group>
        )}
        {(showSyncProgress || allDone) && !showSnapProgress && (
          <Group gap={8} wrap="wrap" align="center" mt={4}>
            <Progress
              value={allDone ? 100 : syncBarFill(progress, syncingBar)}
              size="sm"
              style={{ flex: 1, minWidth: 0 }}
              animated={!allDone && syncingBar}
              striped={!allDone && syncingBar}
              color={allDone || (progress >= 100 && !syncingBar) ? 'teal' : 'cyan'}
            />
            <Text size="xs" c="dimmed" className="mono" style={{ flexShrink: 0 }} lineClamp={1}>
              {syncRightLabel}
            </Text>
          </Group>
        )}
      </Stack>
      </div>
    )
  }

  return (
    <div {...blockProps('node.detail.lifecycle-stepper')}>
    <Stack gap="sm" className="lifecycle-stepper">
      <Group justify="space-between" align="flex-start" wrap="nowrap" className="lifecycle-stepper__head">
        <div style={{ minWidth: 0, flex: 1 }}>
          <Text size="xs" className="lifecycle-stepper__kicker">
            lifecycle
          </Text>
          <Text size="sm" fw={600} lineClamp={2}>
            {label}
          </Text>
          {headDetail && (
            <Text
              size="xs"
              c={(displayCurrent?.status === 'error' || lc?.failed_step) ? 'red' : 'dimmed'}
              lineClamp={2}
            >
              {headDetail}
            </Text>
          )}
        </div>
        <Group gap={6} wrap="nowrap" style={{ flexShrink: 0 }}>
          {displayCurrent && (
            <Badge
              size="sm"
              variant="light"
              color={displayCurrent.status === 'error' || lc?.failed_step ? 'red' : 'teal'}
              className="lifecycle-stepper__count mono"
            >
              {displayCurrent.index}/{displayCurrent.total}
            </Badge>
          )}
          {lc?.node_status && (
            <Badge size="sm" variant="light" color={lc.phase === 'error' ? 'red' : 'gray'}>
              {lc.node_status}
            </Badge>
          )}
        </Group>
      </Group>

      <div className="lifecycle-stepper__steps">
        {steps.map((s, i) => {
          const isCurrent =
            (displayCurrent && s.id === displayCurrent.id) || s.active || s.status === 'active'
          const done = s.status === 'done' || (!!s.done && s.status !== 'skipped')
          const skipped = s.status === 'skipped'
          const err =
            s.error ||
            s.status === 'error' ||
            (!!lc?.failed_step && (s.id || '').toLowerCase() === lc.failed_step.toLowerCase())
          return (
            <Group
              key={s.id || s.title}
              gap={8}
              wrap="nowrap"
              align="flex-start"
              className={`lifecycle-stepper__step${isCurrent ? ' is-current' : ''}${done ? ' is-done' : ''}${err ? ' is-error' : ''}`}
              opacity={isCurrent || done || err || skipped ? 1 : 0.45}
            >
              <ThemeIcon
                size={24}
                radius={0}
                color={stepColor(s)}
                variant={isCurrent ? 'outline' : 'light'}
                className="lifecycle-stepper__step-icon"
                style={{ flexShrink: 0 }}
              >
                {err ? (
                  <IconAlertTriangle size={13} />
                ) : skipped ? (
                  <Text size="xs">–</Text>
                ) : done ? (
                  <IconCheck size={13} />
                ) : (
                  <Text size="xs" className="lifecycle-stepper__step-n">
                    {i + 1}
                  </Text>
                )}
              </ThemeIcon>
              <div className="lifecycle-stepper__step-body">
                <Text size="xs" fw={isCurrent ? 600 : 500} lineClamp={1}>
                  {s.title}
                </Text>
                <Text size="xs" c="dimmed" lineClamp={2} className="mono lifecycle-stepper__step-detail">
                  {s.detail || s.status || (done ? 'done' : 'pending')}
                  {allowSnap && s.id === 'snapshot' && s.pct != null ? ` · ${String(s.pct)}%` : ''}
                </Text>
              </div>
            </Group>
          )
        })}
      </div>

      {showSnapProgress && (
        <Progress
          value={syncBarFill(progress || (status?.snapshot?.wget_running ? 8 : 2), true)}
          size="sm"
          animated
          striped
          radius={0}
          color={lc?.phase === 'error' ? 'red' : 'yellow'}
        />
      )}
      {showSyncProgress && (
        <Group gap={8} wrap="wrap" align="center">
          <Progress
            value={syncBarFill(progress, syncingBar)}
            size="sm"
            style={{ flex: 1, minWidth: 0 }}
            animated={syncingBar}
            striped={syncingBar}
            radius={0}
            color={
              lc?.phase === 'error' ? 'red' : progress >= 100 && !syncingBar ? 'teal' : 'cyan'
            }
          />
          {(progress > 0 || syncingBar || heightVal != null) && (
            <Text size="xs" c="dimmed" className="mono" style={{ flexShrink: 0 }}>
              {syncRightLabel || (progress > 0 ? formatSyncPct(progress) : '…')}
            </Text>
          )}
        </Group>
      )}
    </Stack>
    </div>
  )
}
