import { Badge, Box, Button, Group, Skeleton, Text, ThemeIcon } from '@mantine/core'
import { IconAlertTriangle, IconCheck, IconRefresh } from '@tabler/icons-react'
import { setupLaneRetryLabel } from '../../lib/setupLane'
import { useWizard, type WizardApi } from './wizardContext'

export function WizardRail() {
  return <View {...useWizard()} />
}

function View({
    stepPending,
    currentStep,
    active,
    steps,
    idx,
    failedWizard,
    failedLane,
    error,
    portsError,
    running,
    portsConfirming,
    retryLane,
}: WizardApi) {
  return (
      <Box className="node-install-wizard__rail" p={{ base: 'md', sm: 'lg' }}>
        <Group justify="space-between" align="flex-start" wrap="wrap" gap="sm" mb="sm">
          <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
            Node setup
          </Text>
          {stepPending ? (
            <Skeleton height={22} width={120} radius="sm" />
          ) : (
            currentStep &&
            active !== 'done' && (
              <Badge color="yellow" variant="light">
                {currentStep.headline}
                {currentStep.pct != null ? ` · ${String(currentStep.pct)}%` : ''}
              </Badge>
            )
          )}
        </Group>

        <div className="node-install-wizard__steps" role="list" aria-label="Setup steps">
          {steps.map((s: { id: string; label: string; blurb: string }, i: number) => {
            if (stepPending) {
              return (
                <Group
                  key={s.id}
                  className="node-install-wizard__step"
                  gap="xs"
                  wrap="nowrap"
                  opacity={0.45}
                  role="listitem"
                >
                  <Skeleton height={24} width={24} radius="xl" />
                  <Skeleton height={12} width={64} />
                </Group>
              )
            }
            const isFailed = s.id === failedWizard
            const retryId = s.id === failedWizard && failedLane ? failedLane : null
            const done = !isFailed && (i < idx || active === 'done')
            const current = !isFailed && s.id === active
            return (
              <Group
                key={s.id}
                className={`node-install-wizard__step${current ? ' is-current' : ''}${done ? ' is-done' : ''}${isFailed ? ' is-failed' : ''}`}
                gap="xs"
                wrap="nowrap"
                align="center"
                opacity={isFailed || current || done || active === 'done' ? 1 : 0.45}
                role="listitem"
                title={isFailed ? error || portsError || s.blurb : s.blurb}
              >
                <ThemeIcon
                  size={24}
                  radius={0}
                  color={isFailed ? 'red' : done ? 'teal' : current ? 'teal' : 'gray'}
                  variant={isFailed || done ? 'filled' : current ? 'outline' : 'light'}
                  className="node-install-wizard__step-icon"
                >
                  {isFailed ? (
                    <IconAlertTriangle size={12} />
                  ) : done ? (
                    <IconCheck size={12} />
                  ) : (
                    <span className="node-install-wizard__step-n">{i + 1}</span>
                  )}
                </ThemeIcon>
                <Text
                  size="sm"
                  fw={current || isFailed ? 700 : 500}
                  c={isFailed ? 'red' : undefined}
                  lineClamp={1}
                >
                  {s.label}
                </Text>
                {isFailed && retryId && !running && !portsConfirming ? (
                  <Button
                    size="compact-xs"
                    color="red"
                    variant="light"
                    leftSection={<IconRefresh size={12} />}
                    onClick={() => retryLane(retryId)}
                  >
                    {setupLaneRetryLabel(retryId)}
                  </Button>
                ) : null}
              </Group>
            )
          })}
        </div>
      </Box>

  )
}
