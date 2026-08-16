import { Badge, Group, Progress, Skeleton, Stack, Text, ThemeIcon } from '@mantine/core'
import { IconCheck, IconCircleDot, IconAlertTriangle } from '@tabler/icons-react'
import {
  deriveLifecycleSteps,
  filterLifecycleStepsForNetwork,
  resolveCurrentStep,
  splitStepHeadline,
} from '../lib/nodeLifecycle'
import { supportsSnapshotStep } from '../lib/network'
import type { LifecycleInfo, LifecycleStep, StatusPayload } from '../types'
import { pct } from '../lib/format'

type Props = {
  status?: StatusPayload | null
  lifecycle?: LifecycleInfo | null
  /** Compact single-row for Nodes cards */
  compact?: boolean
  workloadStatus?: string | null
  /** Workload / route network hint (bitcoin → hide snapshot until supported_steps). */
  network?: string | null
  /** Hide agent `ports` step (e.g. add-node contexts). Default: show all agent steps. */
  hidePortsStep?: boolean
  /**
   * False until first status.json attempt finishes.
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

function formatSyncPctLabel(progress: number): string {
  if (progress >= 100) return '100%'
  if (progress >= 10) return `${Math.round(progress)}%`
  const one = Math.round(progress * 10) / 10
  return `${one}%`
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
        <Group gap={6} wrap="nowrap">
          {[0, 1, 2, 3].map((i) => (
            <Skeleton key={i} height={18} width={18} radius="xl" />
          ))}
          <Skeleton height={16} width={96} radius="sm" />
        </Group>
      </Stack>
    )
  }

  return (
    <Stack
      gap="sm"
      p="md"
      style={{
        border: '1px solid var(--mantine-color-dark-4)',
        borderRadius: 12,
        background: 'rgba(0,0,0,0.18)',
      }}
    >
      <Group justify="space-between" align="flex-start">
        <div style={{ flex: 1 }}>
          <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
            Lifecycle
          </Text>
          <Skeleton height={18} width={180} mt={6} />
          <Skeleton height={12} width={240} mt={8} />
        </div>
        <Skeleton height={22} width={48} radius="sm" />
      </Group>
      <Group gap="md" wrap="wrap">
        {[0, 1, 2, 3].map((i) => (
          <Group key={i} gap={8} wrap="nowrap">
            <Skeleton height={28} width={28} radius="xl" />
            <div>
              <Skeleton height={14} width={72} mb={6} />
              <Skeleton height={10} width={100} />
            </div>
          </Group>
        ))}
      </Group>
    </Stack>
  )
}

export function LifecycleStepper({
  status,
  lifecycle,
  compact,
  workloadStatus,
  network,
  hidePortsStep,
  ready,
}: Props) {
  const lc = lifecycle || status?.lifecycle || null
  const allowSnap = supportsSnapshotStep(status, network)
  const hasAgentSteps = !!(lc?.steps && lc.steps.length > 0)
  const hasAgentCursor = !!(
    lc?.current ||
    lc?.current_step_id ||
    lc?.current_step?.id ||
    lc?.current_index != null ||
    lc?.phase
  )

  // Until status.json (or explicit ready) — never paint Install/step 0 as current.
  if (ready === false) {
    return <StepperSkeleton compact={compact} />
  }

  const dataReady = status != null || hasAgentSteps || hasAgentCursor
  if (!dataReady) {
    // Poll finished but no payload yet (unreachable agent) — do not invent Install.
    if (ready === true) {
      return compact ? null : (
        <Text size="sm" c="dimmed" p="md">
          Waiting for agent status…
        </Text>
      )
    }
    return <StepperSkeleton compact={compact} />
  }

  let steps = (hasAgentSteps
    ? filterLifecycleStepsForNetwork(lc!.steps!, status, network)
    : status
      ? deriveLifecycleSteps(status, workloadStatus, network)
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
  const showSnapProgress =
    allowSnap &&
    (lc?.phase || '').toLowerCase() !== 'working' &&
    (lc?.node_status || '').toLowerCase() !== 'online' &&
    !lc?.complete &&
    (active?.id === 'snapshot' ||
      displayCurrent?.id === 'snapshot' ||
      lc?.phase === 'snapshot')
  const curId = (displayCurrent?.id || active?.id || lc?.current || '').toLowerCase()
  const phaseLow = (lc?.phase || '').toLowerCase()
  const nodeStatusLow = (lc?.node_status || '').toLowerCase()
  const hasAgentPct =
    lc?.pct != null &&
    lc.pct !== '' &&
    !Number.isNaN(Number(typeof lc.pct === 'number' ? lc.pct : String(lc.pct).replace('%', '')))
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
      nodeStatusLow === 'syncing' ||
      nodeStatusLow === 'ibd' ||
      bootstrapBusy ||
      !!status?.sync?.ibd ||
      !!status?.sync?.syncing ||
      typeof status?.sync?.verification_pct === 'number' ||
      typeof status?.rpc?.verification_pct === 'number' ||
      typeof status?.sync?.dump_pct === 'number' ||
      hasAgentPct)
  const progressRaw =
    lc?.pct ??
    active?.pct ??
    displayCurrent?.pct ??
    status?.sync?.verification_pct ??
    status?.rpc?.verification_pct ??
    status?.sync?.dump_pct ??
    status?.snapshot?.pct
  const progress =
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
  const heightVal = lc?.height ?? status?.rpc?.node_height ?? null

  if (compact) {
    return (
      <Stack gap={6}>
        <Group gap={6} wrap="nowrap">
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
          <Group gap={8} wrap="nowrap" align="center" mt={4}>
            <Progress
              value={syncBarFill(progress, true)}
              size="sm"
              style={{ flex: 1, minWidth: 0 }}
              animated
              striped
              color="yellow"
            />
            <Text size="xs" c="dimmed" className="mono" style={{ flexShrink: 0 }}>
              {progress > 0 ? formatSyncPctLabel(progress) : '…'}
            </Text>
          </Group>
        )}
        {(showSyncProgress || allDone) && !showSnapProgress && (
          <Group gap={8} wrap="nowrap" align="center" mt={4}>
            <Progress
              value={allDone ? 100 : syncBarFill(progress, syncingBar)}
              size="sm"
              style={{ flex: 1, minWidth: 0 }}
              animated={!allDone && syncingBar}
              striped={!allDone && syncingBar}
              color={allDone || (progress >= 100 && !syncingBar) ? 'teal' : 'cyan'}
            />
            <Text size="xs" c="dimmed" className="mono" style={{ flexShrink: 0 }} lineClamp={1}>
              {allDone && heightVal != null
                ? network === 'solana'
                  ? `slot ${Number(heightVal).toLocaleString()}`
                  : `${Number(heightVal).toLocaleString()} blk`
                : progress > 0 || syncingBar
                  ? progress > 0
                    ? formatSyncPctLabel(progress)
                    : '…'
                  : ''}
            </Text>
          </Group>
        )}
      </Stack>
    )
  }

  return (
    <Stack
      gap="sm"
      p="md"
      style={{
        border: '1px solid var(--mantine-color-dark-4)',
        borderRadius: 12,
        background: 'rgba(0,0,0,0.18)',
      }}
    >
      <Group justify="space-between" align="flex-start">
        <div>
          <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
            Lifecycle
          </Text>
          <Text size="sm" fw={700}>
            {label}
          </Text>
          {detail && (
            <Text size="xs" c="dimmed">
              {detail}
            </Text>
          )}
        </div>
        <Group gap={6}>
          {displayCurrent && (
            <Badge size="sm" variant="filled" color="yellow">
              {displayCurrent.index}/{displayCurrent.total}
            </Badge>
          )}
          {lc?.node_status && (
            <Badge size="sm" variant="light" color={lc.phase === 'error' ? 'red' : 'cyan'}>
              {lc.node_status}
            </Badge>
          )}
        </Group>
      </Group>

      <Group gap="md" wrap="wrap">
        {steps.map((s, i) => {
          const isCurrent =
            (displayCurrent && s.id === displayCurrent.id) || s.active || s.status === 'active'
          const done = s.status === 'done' || (!!s.done && s.status !== 'skipped')
          const skipped = s.status === 'skipped'
          const err = s.error || s.status === 'error'
          return (
            <Group
              key={s.id || s.title}
              gap={8}
              wrap="nowrap"
              opacity={isCurrent || done || err || skipped ? 1 : 0.45}
            >
              <ThemeIcon
                size={28}
                radius="xl"
                color={stepColor(s)}
                variant={isCurrent ? 'filled' : 'light'}
              >
                {err ? (
                  <IconAlertTriangle size={14} />
                ) : skipped ? (
                  <Text size="xs">–</Text>
                ) : done ? (
                  <IconCheck size={14} />
                ) : (
                  <Text size="xs">{i + 1}</Text>
                )}
              </ThemeIcon>
              <div>
                <Text size="sm" fw={isCurrent ? 700 : 500}>
                  {s.title}
                </Text>
                <Text size="xs" c="dimmed" lineClamp={1}>
                  {s.detail || s.status || (done ? 'done' : 'pending')}
                  {allowSnap && s.id === 'snapshot' && s.pct != null ? ` · ${String(s.pct)}%` : ''}
                </Text>
              </div>
            </Group>
          )
        })}
      </Group>

      {showSnapProgress && (
        <Progress
          value={syncBarFill(progress || (status?.snapshot?.wget_running ? 8 : 2), true)}
          size="md"
          animated
          striped
          color={lc?.phase === 'error' ? 'red' : 'yellow'}
        />
      )}
      {showSyncProgress && (
        <Group gap={8} wrap="nowrap" align="center">
          <Progress
            value={syncBarFill(progress, syncingBar)}
            size="md"
            style={{ flex: 1, minWidth: 0 }}
            animated={syncingBar}
            striped={syncingBar}
            color={
              lc?.phase === 'error' ? 'red' : progress >= 100 && !syncingBar ? 'teal' : 'cyan'
            }
          />
          {(progress > 0 || syncingBar) && (
            <Text size="sm" c="dimmed" className="mono" style={{ flexShrink: 0 }}>
              {progress > 0 ? formatSyncPctLabel(progress) : '…'}
            </Text>
          )}
        </Group>
      )}
    </Stack>
  )
}
