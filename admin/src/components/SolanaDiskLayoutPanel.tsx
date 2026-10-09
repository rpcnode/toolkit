import { Alert, Code, Group, Select, Stack, Text } from '@mantine/core'
import { IconDatabase } from '@tabler/icons-react'
import type { HostMountInfo, SolanaDiskLayoutPlan } from '../api'
import { pathOnDataMount } from '../lib/networkPaths'

type Props = {
  loading?: boolean
  error?: string | null
  mounts: HostMountInfo[]
  recommended: SolanaDiskLayoutPlan | null
  layout: SolanaDiskLayoutPlan | null
  rules?: string[]
  onChange: (next: SolanaDiskLayoutPlan) => void
  onUseRecommended: () => void
}

function mountLabel(m: HostMountInfo): string {
  const bits = [
    m.target,
    m.tran ? m.tran.toUpperCase() : m.preferred ? 'SSD' : m.rota ? 'HDD' : undefined,
    m.avail_human ? `${m.avail_human} free` : undefined,
    m.model,
    m.auto_mount ? 'desktop mount, not mounted at boot' : undefined,
  ].filter(Boolean)
  return bits.join(' · ')
}

function pathOnMount(mount: string, envLeaf: string, role: 'ledger' | 'accounts' | 'snapshots'): string {
  return pathOnDataMount(mount, 'solana', envLeaf, role)
}

function uniqueMountOptions(items: { value: string; label: string }[]): { value: string; label: string }[] {
  const seen = new Set<string>()
  const out: { value: string; label: string }[] = []
  for (const o of items) {
    if (!o.value || seen.has(o.value)) continue
    seen.add(o.value)
    out.push(o)
  }
  return out
}

export function SolanaDiskLayoutPanel({
  loading,
  error,
  mounts,
  recommended,
  layout,
  rules,
  onChange,
  onUseRecommended,
}: Props) {
  const options = uniqueMountOptions(
    mounts
      .filter((m) => !!m.target && m.target !== '/')
      .map((m) => ({
        value: m.target,
        label: mountLabel(m),
      })),
  )
  // /data on the OS disk is not a choice. Empty NVMe appear as /data/nvmeN.

  const envLeaf =
    (layout?.ledger_dir || recommended?.ledger_dir || '')
      .split('/')
      .filter(Boolean)
      .find((_, i, arr) => arr[i - 1] === 'solana') || 'mainnet'

  function setMount(role: 'ledger' | 'accounts' | 'snapshots', mount: string | null) {
    if (!mount) return
    const base: SolanaDiskLayoutPlan = {
      ...(layout || recommended || {}),
      strategy: layout?.strategy || recommended?.strategy || 'custom',
    }
    if (role === 'ledger') {
      base.ledger_mount = mount
      base.ledger_dir = pathOnMount(mount, envLeaf, 'ledger')
    } else if (role === 'accounts') {
      base.accounts_mount = mount
      base.accounts_dir = pathOnMount(mount, envLeaf, 'accounts')
    } else {
      base.snapshots_mount = mount
      base.snapshots_dir = pathOnMount(mount, envLeaf, 'snapshots')
    }
    // Recompute strategy hint from distinct mounts.
    const set = new Set(
      [base.ledger_mount, base.accounts_mount, base.snapshots_mount].filter(Boolean) as string[],
    )
    base.strategy = set.size >= 3 ? 'jbod_3' : set.size === 2 ? 'jbod_2' : 'single'
    onChange(base)
  }

  return (
    <Alert
      color="grape"
      variant="light"
      icon={<IconDatabase size={16} />}
      title="Solana disk layout (JBOD)"
    >
      <Stack gap="sm" mt={4}>
        <Text size="sm" c="dimmed">
          Prefer separate NVMe for ledger and accounts (not one RAID). Snapshots on a third disk when
          possible; otherwise with accounts.
        </Text>
        {loading && (
          <Text size="xs" c="dimmed">
            Loading tip disk inventory…
          </Text>
        )}
        {error && (
          <Text size="xs" c="orange">
            {error} — Install stays blocked until a data NVMe is visible.
          </Text>
        )}
        {!!rules?.length && (
          <Text size="xs" c="dimmed">
            {rules[0]}
          </Text>
        )}
        <Select
          label="Ledger"
          description="Agave --ledger (highest IOPS)"
          data={options}
          value={layout?.ledger_mount || recommended?.ledger_mount || options[0]?.value || ''}
          onChange={(v) => setMount('ledger', v)}
          searchable
          allowDeselect={false}
        />
        <Select
          label="Accounts"
          description="Agave --accounts"
          data={options}
          value={layout?.accounts_mount || recommended?.accounts_mount || options[0]?.value || ''}
          onChange={(v) => setMount('accounts', v)}
          searchable
          allowDeselect={false}
        />
        <Select
          label="Snapshots"
          description="Agave --snapshots"
          data={options}
          value={layout?.snapshots_mount || recommended?.snapshots_mount || options[0]?.value || ''}
          onChange={(v) => setMount('snapshots', v)}
          searchable
          allowDeselect={false}
        />
        <Code block className="mono">
          {[
            `ledger:    ${layout?.ledger_dir || recommended?.ledger_dir || '—'}`,
            `accounts:  ${layout?.accounts_dir || recommended?.accounts_dir || '—'}`,
            `snapshots: ${layout?.snapshots_dir || recommended?.snapshots_dir || '—'}`,
            `strategy:  ${layout?.strategy || recommended?.strategy || '—'}`,
          ].join('\n')}
        </Code>
        <Group justify="space-between">
          <Text size="xs" c="dimmed">
            Tip recommended: {recommended?.strategy || '—'}
          </Text>
          <Text
            size="xs"
            c="cyan"
            style={{ cursor: 'pointer', textDecoration: 'underline' }}
            onClick={onUseRecommended}
          >
            Reset to recommended
          </Text>
        </Group>
      </Stack>
    </Alert>
  )
}
