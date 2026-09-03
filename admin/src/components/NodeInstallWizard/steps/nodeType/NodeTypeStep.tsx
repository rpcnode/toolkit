import { Alert, Badge, Box, Button, Group, Stack, Text, Title } from '@mantine/core'
import { IconArrowRight } from '@tabler/icons-react'
import { blockProps } from '../../../../lib/blockId'
import { InstallOptionsPicker } from '../../../InstallOptionsPicker'
import { useWizard, type WizardApi } from '../../wizardContext'


export function NodeTypeStep() {
  return <View {...useWizard()} />
}

function View({
    nodeTypeOptionGroups,
    nodeTypeStepLabel,
    installOptions,
    setInstallOptions,
    continueFromNodeType,
    setUiStep,
    manualBackToDisks,
    manualBackToNodeType,
    manualBackToPorts,
    agentAckedStep,
    error,
}: WizardApi) {
  return (
    <>
          
<Box {...blockProps('node.detail.wizard.step.node-type')}>
              <Stack gap="md">
                <Group justify="space-between">
                  <Title order={3}>{nodeTypeStepLabel}</Title>
                  <Badge color="gray" variant="light">
                    choose
                  </Badge>
                </Group>
                <Text c="dimmed" size="sm">
                  Pick how this node stores history before Snapshot / Start. Disk layout is already
                  saved on the previous step.
                </Text>

                <InstallOptionsPicker
                  groups={nodeTypeOptionGroups}
                  value={installOptions}
                  onChange={setInstallOptions}
                />

                {error && (
                  <Alert color="red" title="Setup failed">
                    <Text size="sm">{error}</Text>
                  </Alert>
                )}

                <Group
                  className="node-install-wizard__actions"
                  justify="space-between"
                  wrap="wrap"
                  mt="sm"
                  gap="sm"
                >
                  <Button
                    variant="default"
                    size="md"
                    className="node-install-wizard__actions-back"
                    onClick={() => {
                      manualBackToPorts.current = false
                      manualBackToNodeType.current = false
                      manualBackToDisks.current = true
                      agentAckedStep.current = 'disks'
                      setUiStep('disks')
                    }}
                  >
                    Back
                  </Button>
                  <Button
                    className="node-install-wizard__actions-primary"
                    color="teal"
                    size="md"
                    rightSection={<IconArrowRight size={16} />}
                    disabled={
                      nodeTypeOptionGroups.some((g: any) => !(installOptions[g.id] || g.default || g.choices[0]?.id))
                    }
                    onClick={() => void continueFromNodeType()}
                  >
                    Continue
                  </Button>
                </Group>
              </Stack>
            </Box>
    
    </>
  )
}
