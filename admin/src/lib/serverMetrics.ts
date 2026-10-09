import type { ServerMetrics } from '../api'
import type { HostDiskIOHistory, MetricPoint, MetricsPayload } from '../types'

/** Keep ~30 min at 15s poll, or ~10 min at 5s. */
const MAX_HISTORY_POINTS = 120

/** Map panel-cached agent heartbeat metrics into MetricCharts' MetricsPayload shape. */
export function serverMetricsToPayload(
  metrics?: ServerMetrics | null,
  opts?: { updatedAt?: string | null; status?: string | null },
): MetricsPayload | null {
  if (!metrics) return null
  const hasAny =
    metrics.cpu_pct != null ||
    metrics.mem_pct != null ||
    metrics.load_1 != null ||
    metrics.disk_used_pct != null ||
    metrics.net_rx_mbps != null ||
    metrics.disk_read_iops != null ||
    metrics.disk_util_pct != null ||
    (metrics.disks && metrics.disks.length > 0)
  if (!hasAny) return null

  const disks =
    metrics.disks && metrics.disks.length > 0
      ? metrics.disks
          .filter((d) => (d.name || d.mount || '').trim())
          .map((d) => ({
            name: (d.name || d.mount || 'disk').trim(),
            mount: d.mount,
            free_gb: d.free_gb,
            total_gb: d.total_gb,
            used_pct: d.used_pct,
            read_iops: d.read_iops,
            write_iops: d.write_iops,
            read_mb_s: d.read_mb_s,
            write_mb_s: d.write_mb_s,
            util_pct: d.util_pct,
          }))
      : metrics.disk_total_gb != null && metrics.disk_total_gb > 0
        ? [
            {
              name: 'host',
              free_gb: Math.max(
                0,
                Number(metrics.disk_total_gb) - Number(metrics.disk_used_gb || 0),
              ),
              total_gb: metrics.disk_total_gb,
              used_pct: metrics.disk_used_pct,
              util_pct: metrics.disk_util_pct,
              read_iops: metrics.disk_read_iops,
              write_iops: metrics.disk_write_iops,
              read_mb_s: metrics.disk_read_mb_s,
              write_mb_s: metrics.disk_write_mb_s,
            },
          ]
        : undefined

  return {
    ok: true,
    updated_at: opts?.updatedAt || metrics.collected_at || metrics.last_seen_at,
    current: {
      cpu_pct: metrics.cpu_pct,
      mem_pct: metrics.mem_pct,
      mem_used_mb: metrics.mem_used_mb,
      mem_total_mb: metrics.mem_total_mb,
      load_1: metrics.load_1,
      net_rx_mbps: metrics.net_rx_mbps,
      net_tx_mbps: metrics.net_tx_mbps,
      disk_read_iops: metrics.disk_read_iops,
      disk_write_iops: metrics.disk_write_iops,
      disk_read_mb_s: metrics.disk_read_mb_s,
      disk_write_mb_s: metrics.disk_write_mb_s,
      disk_util_pct: metrics.disk_util_pct,
      disk_busy: metrics.disk_busy,
      disks,
    },
  }
}

export function hasServerMetrics(metrics?: ServerMetrics | null): boolean {
  return serverMetricsToPayload(metrics) != null
}

/**
 * `server_metrics` stores only the latest host snapshot. Charts need a time
 * series — fold each distinct `collected_at` into a rolling client buffer.
 */
export function appendHostMetricsHistory(
  prev: MetricsPayload['history'] | undefined,
  current: NonNullable<MetricsPayload['current']>,
  collectedAt?: string | null,
): MetricsPayload['history'] {
  const t = sampleEpochSec(collectedAt)
  const lastT =
    prev?.cpu?.[prev.cpu.length - 1]?.t ??
    prev?.net_rx?.[prev.net_rx.length - 1]?.t ??
    prev?.disk_read_iops?.[prev.disk_read_iops.length - 1]?.t
  if (lastT != null && lastT === t) {
    return prev || {}
  }

  return {
    cpu: pushPoint(prev?.cpu, t, current.cpu_pct),
    memory: pushPoint(prev?.memory, t, current.mem_pct),
    load: pushPoint(prev?.load, t, current.load_1),
    net_rx: pushPoint(prev?.net_rx, t, current.net_rx_mbps),
    net_tx: pushPoint(prev?.net_tx, t, current.net_tx_mbps),
    disk_read_iops: pushPoint(prev?.disk_read_iops, t, current.disk_read_iops),
    disk_write_iops: pushPoint(prev?.disk_write_iops, t, current.disk_write_iops),
    disk_util: pushPoint(prev?.disk_util, t, current.disk_util_pct),
    disks: appendDiskHistory(prev?.disks, current.disks, t),
  }
}

function sampleEpochSec(collectedAt?: string | null): number {
  if (collectedAt) {
    const ms = Date.parse(collectedAt)
    if (Number.isFinite(ms)) return Math.floor(ms / 1000)
  }
  return Math.floor(Date.now() / 1000)
}

function pushPoint(
  arr: MetricPoint[] | undefined,
  t: number,
  v: number | undefined | null,
): MetricPoint[] | undefined {
  if (v == null || Number.isNaN(Number(v))) return arr
  const next = [...(arr || []), { t, v: Number(v) }]
  return next.length > MAX_HISTORY_POINTS ? next.slice(-MAX_HISTORY_POINTS) : next
}

function appendDiskHistory(
  prev: HostDiskIOHistory[] | undefined,
  disks: NonNullable<MetricsPayload['current']>['disks'],
  t: number,
): HostDiskIOHistory[] | undefined {
  if (!disks?.length) return prev
  const byName = new Map((prev || []).map((h) => [h.name, { ...h }]))
  for (const d of disks) {
    const name = (d.name || '').trim()
    if (!name) continue
    const row = byName.get(name) || { name }
    row.read_iops = pushPoint(row.read_iops, t, d.read_iops)
    row.write_iops = pushPoint(row.write_iops, t, d.write_iops)
    row.util = pushPoint(row.util, t, d.util_pct)
    byName.set(name, row)
  }
  return [...byName.values()]
}
