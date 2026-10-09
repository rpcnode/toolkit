import { Alert, Code, Stack, Text } from '@mantine/core'
import { IconAlertTriangle } from '@tabler/icons-react'
import type { ApiCallResult } from '../api'

type Props = {
  title?: string
  result: Pick<ApiCallResult<unknown>, 'ok' | 'status' | 'request' | 'error' | 'message'>
  /** Extra context (e.g. log path from response body). */
  detail?: string | null
}

/** Shows which panel API call failed and the HTTP/body error — never hide failures as empty state. */
export function ApiFetchIssue({ title = 'Request failed', result, detail }: Props) {
  if (result.ok) return null
  const msg = result.message || result.error || (result.status ? `HTTP ${result.status}` : 'Network error')
  return (
    <Alert color="red" icon={<IconAlertTriangle size={14} />} title={title}>
      <Stack gap={4}>
        <Code className="mono" style={{ fontSize: 11, wordBreak: 'break-all' }}>
          {result.request} → {result.status || '—'}
          {result.error ? ` · ${result.error}` : ''}
        </Code>
        <Text size="xs">{msg}</Text>
        {detail ? (
          <Text size="xs" c="dimmed" className="mono" style={{ wordBreak: 'break-all' }}>
            {detail}
          </Text>
        ) : null}
      </Stack>
    </Alert>
  )
}

export function apiIssueSummary(result: Pick<ApiCallResult<unknown>, 'request' | 'status' | 'error' | 'message'>): string {
  const msg = result.message || result.error || `HTTP ${result.status}`
  return `${result.request} → ${result.status || '—'}: ${msg}`
}
