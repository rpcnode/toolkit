import { useEffect, useState } from 'react'
import { Alert, Text } from '@mantine/core'
import { IconPlugConnectedX } from '@tabler/icons-react'
import { blockProps } from '../lib/blockId'

/** The host agent pushes heights every 30 s; this long without one means it no longer reports the node. */
const STALE_AFTER_MS = 5 * 60_000

function ago(ms: number): string {
  const min = Math.round(ms / 60_000)
  if (min < 60) return `${min} min`
  const h = Math.floor(min / 60)
  return h < 24 ? `${h} h` : `${Math.floor(h / 24)} d`
}

/**
 * Warns when a running node has stopped reporting (height / disk never arrive, so the progress bar sits at 0%).
 * Typical cause: the host agent lost the node from its registry — Stop → Start registers it again.
 */
export function NodeReportingNotice({ status, heightAt }: { status?: string; heightAt?: string | null }) {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const t = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(t)
  }, [])

  const s = (status || '').toLowerCase()
  if (s !== 'sync' && s !== 'active') return null
  const at = heightAt ? Date.parse(heightAt) : NaN
  const age = Number.isFinite(at) ? now - at : null
  if (age != null && age < STALE_AFTER_MS) return null

  return (
    <Alert
      color="orange"
      variant="light"
      icon={<IconPlugConnectedX size={18} />}
      title="No data from the host agent"
      {...blockProps('node.detail.not-reporting')}
    >
      <Text size="sm">
        {age == null
          ? 'This node has never reported its height.'
          : `Last height report was ${ago(age)} ago.`}{' '}
        The sync bar and the stage below cannot be trusted until it reports again. Restart the node (Stop, then Start)
        so the agent registers it again; if it stays silent, check the agent on the host.
      </Text>
    </Alert>
  )
}
