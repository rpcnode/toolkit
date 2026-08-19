import { useMemo, useState, type ReactNode } from 'react'
import { Badge, Card, Grid, Group, Progress, SimpleGrid, Stack, Tabs, Text, Title, Tooltip } from '@mantine/core'
import { AreaChart } from '@mantine/charts'
import { IconHelp } from '@tabler/icons-react'
import type { HostDiskIO, HostDiskIOHistory, MetricsPayload, StatusPayload } from '../types'
import { chartPairSeries, chartNetSeries, chartSeries, formatStorageGiB, fmtBytesGiB, fmtDiskFree, fmtDiskMBs, fmtIOPS, fmtMbps, num } from '../lib/format'

type RpcSnap = {
  rps_1m?: number
  rps_5m?: number
  in_flight?: number
  total?: number
  latency_p50_ms?: number
  latency_p95_ms?: number
  errors_4xx?: number
  errors_5xx?: number
  http_502?: number
  http_503?: number
  upstream_errors?: number
  maintenance_hits?: number
}

function pickRpc(
  metrics: MetricsPayload | null,
  statusMetrics?: StatusPayload['metrics'],
  cached?: RpcSnap | null,
): RpcSnap {
  const cur = metrics?.current
  const gw = metrics?.gateway
  const st = statusMetrics
  const c = cached || undefined
  return {
    rps_1m: cur?.rps_1m ?? gw?.rps_1m ?? st?.rps_1m ?? c?.rps_1m,
    rps_5m: cur?.rps_5m ?? gw?.rps_5m ?? st?.rps_5m ?? c?.rps_5m,
    in_flight: cur?.in_flight ?? gw?.in_flight ?? st?.in_flight ?? c?.in_flight,
    total: gw?.total ?? st?.total ?? c?.total,
    latency_p50_ms: cur?.latency_p50_ms ?? gw?.latency_p50_ms ?? st?.latency_p50_ms ?? c?.latency_p50_ms,
    latency_p95_ms: cur?.latency_p95_ms ?? gw?.latency_p95_ms ?? st?.latency_p95_ms ?? c?.latency_p95_ms,
    errors_4xx: gw?.errors_4xx ?? st?.errors_4xx ?? c?.errors_4xx,
    errors_5xx: gw?.errors_5xx ?? st?.errors_5xx ?? c?.errors_5xx,
    http_502: gw?.http_502 ?? st?.http_502 ?? c?.http_502,
    http_503: gw?.http_503 ?? st?.http_503 ?? c?.http_503,
    upstream_errors: gw?.upstream_errors ?? st?.upstream_errors ?? c?.upstream_errors,
    maintenance_hits: gw?.maintenance_hits ?? st?.maintenance_hits ?? c?.maintenance_hits,
  }
}

function hasRpc(rpc: RpcSnap): boolean {
  // rps_1m === 0 is a valid idle proxy sample — still show the panel.
  return (
    (rpc.rps_1m != null && !Number.isNaN(rpc.rps_1m)) ||
    (rpc.rps_5m != null && !Number.isNaN(rpc.rps_5m)) ||
    (rpc.latency_p50_ms != null && !Number.isNaN(rpc.latency_p50_ms)) ||
    (rpc.latency_p95_ms != null && !Number.isNaN(rpc.latency_p95_ms)) ||
    (rpc.in_flight != null && !Number.isNaN(rpc.in_flight)) ||
    (rpc.total != null && !Number.isNaN(rpc.total)) ||
    (rpc.errors_5xx != null && !Number.isNaN(rpc.errors_5xx))
  )
}

/** Idle zeros when leaf proxy is up but no traffic yet. */
function idleRpcSnap(): RpcSnap {
  return {
    rps_1m: 0,
    rps_5m: 0,
    in_flight: 0,
    total: 0,
    latency_p50_ms: 0,
    latency_p95_ms: 0,
    errors_4xx: 0,
    errors_5xx: 0,
    http_502: 0,
    http_503: 0,
    upstream_errors: 0,
    maintenance_hits: 0,
  }
}

type Level = 'ok' | 'warn' | 'critical' | undefined

function levelColor(level?: Level): string | undefined {
  switch (level) {
    case 'ok':
      return 'teal.4'
    case 'warn':
      return 'yellow.4'
    case 'critical':
      return 'red.4'
    default:
      return undefined
  }
}

function latencyLevel(ms?: number): Level {
  if (ms == null || Number.isNaN(ms)) return undefined
  if (ms >= 2000) return 'critical'
  if (ms >= 800) return 'warn'
  return 'ok'
}

function diskUtilLevel(pct?: number): Level {
  if (pct == null || Number.isNaN(pct)) return undefined
  if (pct >= 90) return 'critical'
  if (pct >= 70) return 'warn'
  return 'ok'
}

function rpsLevel(rps?: number): Level {
  if (rps == null || Number.isNaN(rps)) return undefined
  if (rps >= 5000) return 'critical'
  if (rps >= 1000) return 'warn'
  return 'ok'
}

function errLevel(n?: number): Level {
  if (n == null || Number.isNaN(n) || n <= 0) return 'ok'
  if (n >= 100) return 'critical'
  return 'warn'
}

export function MetricCharts({
  metrics,
  statusMetrics,
  cachedRpc,
  /** Node past Confirm ports / running — show Fullnode Go RPC tiles even before first sample. */
  forceRpcPanel = false,
  /** Hide Fullnode Go RPC during setup wizard (Host charts stay). */
  showRpcPanel = true,
  /** Sync status (or other) — rendered first, above Host. */
  sidePanel,
}: {
  metrics: MetricsPayload | null
  /** Fallback from status.json `metrics` when /api/metrics.json is thin. */
  statusMetrics?: StatusPayload['metrics']
  /** SQLite-cached rpc_proxy from collector. */
  cachedRpc?: RpcSnap | null
  forceRpcPanel?: boolean
  showRpcPanel?: boolean
  sidePanel?: ReactNode
}) {
  const cur = metrics?.current
  const hist = metrics?.history
  let rpc = pickRpc(metrics, statusMetrics, cachedRpc)
  const showRpc = showRpcPanel && (hasRpc(rpc) || forceRpcPanel)
  if (showRpcPanel && forceRpcPanel && !hasRpc(rpc)) {
    rpc = idleRpcSnap()
  }
  const rps = chartSeries(hist?.rps, 'rps')
  const cpu = chartSeries(hist?.cpu, 'cpu')
  const load = chartSeries(hist?.load, 'load')
  const mem = chartSeries(hist?.memory, 'mem')
  const hostNet = chartNetSeries(hist?.net_rx, hist?.net_tx)
  const nodeNet = chartNetSeries(hist?.node_net_rx, hist?.node_net_tx)
  const nodeCpu = chartSeries(hist?.node_cpu, 'cpu')
  const nodeMem = chartSeries(hist?.node_memory, 'mem')
  const diskIO = chartPairSeries(hist?.disk_read_iops, hist?.disk_write_iops, 'read', 'write')
  const diskUtil = chartSeries(hist?.disk_util, 'util')
  const nodeDiskIO = chartPairSeries(hist?.node_disk_read_iops, hist?.node_disk_write_iops, 'read', 'write')
  const hasNode = cur?.node_net_rx_mbps != null || cur?.node_cpu_pct != null || cur?.node_mem_pct != null
  const hasDisk = cur?.disk_read_iops != null || cur?.disk_write_iops != null || cur?.disk_util_pct != null
  const hasNodeDisk = cur?.node_disk_read_iops != null || cur?.node_disk_write_iops != null

  const hostCard = (
    <Card>
      <Group justify="space-between" mb="md" wrap="wrap" gap="sm">
        <div>
          <Title order={3}>Host & node</Title>
          <Text c="dimmed" size="xs">
            Host NIC/CPU/RAM/disk · this node unit (cgroup accounting)
          </Text>
        </div>
        <Group gap="md" wrap="wrap">
          <Stat
            label="Host CPU"
            value={num(cur?.cpu_pct, 1)}
            hint="Host CPU busy percent for this server."
          />
          <Stat
            label="Node CPU"
            value={num(cur?.node_cpu_pct, 1)}
            hint="This node unit CPU as % of all host cores (systemd CPUAccounting)."
          />
          <Stat
            label="Host Mem"
            value={num(cur?.mem_pct, 1)}
            hint="Host RAM used percent on this server."
          />
          <Stat
            label="Node Mem"
            value={num(cur?.node_mem_pct, 1)}
            hint="Node anonymous RAM (cgroup anon / RSS) as % of host MemTotal — same basis as Host Mem (excludes blockchain page cache)."
          />
          <Stat
            label="Host Net ↓"
            value={fmtMbps(cur?.net_rx_mbps)}
            hint="Host NIC receive (Mbps)."
          />
          <Stat
            label="Node Net ↓"
            value={fmtMbps(cur?.node_net_rx_mbps)}
            hint="Node unit IP ingress (Mbps)."
          />
          <Stat
            label="Load 1"
            value={num(cur?.load_1, 2)}
            hint="OS load average over 1 minute."
            compact
          />
          <Stat
            label="Disk Read"
            value={fmtIOPS(cur?.disk_read_iops)}
            hint={`Host 4k-equivalent read IOPS (completed reads / s, /proc/diskstats). ${fmtDiskMBs(cur?.disk_read_mb_s)}`}
          />
          <Stat
            label="Disk Write"
            value={fmtIOPS(cur?.disk_write_iops)}
            hint={`Host write IOPS (completed writes / s). ${fmtDiskMBs(cur?.disk_write_mb_s)}`}
          />
          <Stat
            label="Disk load"
            value={cur?.disk_util_pct == null || Number.isNaN(cur.disk_util_pct) ? '—' : `${num(cur.disk_util_pct, 1)}%`}
            hint={`Disk busy percent (iostat %util) — how loaded the hottest disk is${cur?.disk_busy ? ` (${cur.disk_busy})` : ''}. 70% warn, 90%+ saturated.`}
            level={diskUtilLevel(cur?.disk_util_pct)}
          />
          {hasNodeDisk ? (
            <>
              <Stat
                label="Node Read"
                value={fmtIOPS(cur?.node_disk_read_iops)}
                hint={`This node unit read IOPS (cgroup io.stat). ${fmtDiskMBs(cur?.node_disk_read_mb_s)}`}
              />
              <Stat
                label="Node Write"
                value={fmtIOPS(cur?.node_disk_write_iops)}
                hint={`This node unit write IOPS (cgroup io.stat). ${fmtDiskMBs(cur?.node_disk_write_mb_s)}`}
              />
            </>
          ) : null}
        </Group>
      </Group>
      {cur?.node_net_rx_bytes != null || cur?.node_net_tx_bytes != null ? (
        <Text c="dimmed" size="xs" mb="sm">
          Node Σ ↓ {fmtBytesGiB(cur?.node_net_rx_bytes)} · ↑ {fmtBytesGiB(cur?.node_net_tx_bytes)}
          {cur?.node_mem_used_mb != null ? ` · RAM ${num(cur.node_mem_used_mb, 0)} MiB` : ''}
        </Text>
      ) : null}

      <Grid mb="md">
        <Grid.Col span={{ base: 12, md: 6 }}>
          <NetChartCard
            title="Host network (Mbps)"
            hint="Whole-server NIC throughput (/proc/net/dev)."
            data={hostNet}
            rxLabel={`↓ ${fmtMbps(cur?.net_rx_mbps)}`}
            txLabel={`↑ ${fmtMbps(cur?.net_tx_mbps)}`}
          />
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 6 }}>
          <NetChartCard
            title="Node network (Mbps)"
            hint="This node unit only (systemd IPAccounting)."
            data={nodeNet}
            rxLabel={`↓ ${fmtMbps(cur?.node_net_rx_mbps)}`}
            txLabel={`↑ ${fmtMbps(cur?.node_net_tx_mbps)}`}
            emptyHint={
              hasNode
                ? 'Collecting node network samples…'
                : 'Node accounting not ready yet (Update agent / wait ~10s)'
            }
          />
        </Grid.Col>
      </Grid>

      <DisksPanel
        disks={cur?.disks}
        history={hist?.disks}
        host={{
          readIops: cur?.disk_read_iops,
          writeIops: cur?.disk_write_iops,
          readMBs: cur?.disk_read_mb_s,
          writeMBs: cur?.disk_write_mb_s,
          utilPct: cur?.disk_util_pct,
          busy: cur?.disk_busy,
        }}
        hostIO={diskIO}
        hostUtil={diskUtil}
        hasDisk={hasDisk}
      />

      {hasNodeDisk || nodeDiskIO.length ? (
        <Card padding="sm" className="metric-chart-panel" mb="md">
          <PairChartCard
            title="Node disk IOPS"
            hint="This node unit only (cgroup io.stat). Needs IOAccounting — enabled on Update / ensure."
            data={nodeDiskIO}
            aKey="read"
            bKey="write"
            aColor="teal.5"
            bColor="orange.5"
            aLabel={`Read ${fmtIOPS(cur?.node_disk_read_iops)} · ${fmtDiskMBs(cur?.node_disk_read_mb_s)}`}
            bLabel={`Write ${fmtIOPS(cur?.node_disk_write_iops)} · ${fmtDiskMBs(cur?.node_disk_write_mb_s)}`}
            emptyHint="Collecting node disk samples…"
            bare
          />
        </Card>
      ) : null}

      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md" mb="md">
        <ChartCard
          title="Host CPU %"
          hint="Server CPU busy percent."
          data={cpu}
          dataKey="cpu"
          color="cyan.5"
        />
        <ChartCard
          title="Node CPU %"
          hint="Node unit CPU as % of all host cores."
          data={nodeCpu}
          dataKey="cpu"
          color="indigo.5"
          emptyHint={hasNode ? 'Collecting…' : 'Node CPU accounting not ready'}
        />
      </SimpleGrid>
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md" mb="md">
        <ChartCard
          title="Host memory %"
          hint="Server RAM used percent."
          data={mem}
          dataKey="mem"
          color="yellow.5"
        />
        <ChartCard
          title="Node memory %"
          hint="Node anonymous RAM % of host — excludes file cache (unlike MemoryCurrent)."
          data={nodeMem}
          dataKey="mem"
          color="orange.5"
          emptyHint={hasNode ? 'Collecting…' : 'Node memory accounting not ready'}
        />
      </SimpleGrid>

      <Card padding="sm" className="metric-chart-panel">
        <Group gap={4} wrap="nowrap" align="center" mb={4}>
          <Text size="xs" c="dimmed" fw={600}>
            Load average (1m)
          </Text>
          <MetricHelp label="OS load average over 1 minute — run-queue length. Compare to CPU count." />
        </Group>
        {load.length ? (
          <AreaChart
            h={100}
            data={load}
            dataKey="time"
            series={[{ name: 'load', color: 'grape.4' }]}
            curveType="monotone"
            withDots={false}
            gridAxis="xy"
            tickLine="none"
            withXAxis={false}
            strokeWidth={2}
            fillOpacity={0.2}
          />
        ) : (
          <Text c="dimmed" size="xs" py="sm" ta="center">
            Collecting…
          </Text>
        )}
      </Card>
    </Card>
  )

  return (
    <Stack gap="md">
      {/* Sync first — then Host (full width) — then Fullnode Go RPC. */}
      {sidePanel}
      {hostCard}

      {showRpc ? (
      <Card className="fullnode-rpc-card">
        <Group justify="space-between" mb="md" wrap="wrap" gap="sm">
          <div>
            <Group gap="xs" align="center">
              <Title order={3}>Fullnode Go RPC</Title>
              <Badge size="sm" variant="light" color="teal">
                public proxy
              </Badge>
            </Group>
          </div>
          <Text size="xs" c="dimmed" className="mono">
            total {num(rpc.total, 0)} req
            {rpc.maintenance_hits != null && rpc.maintenance_hits > 0
              ? ` · maint ${num(rpc.maintenance_hits, 0)}`
              : ''}
          </Text>
        </Group>

        <SimpleGrid cols={{ base: 2, sm: 3, md: 6 }} spacing="sm" mb="md">
          <RpcTile
            label="RPS 1m"
            value={num(rpc.rps_1m, 1)}
            hint="Requests per second through the public fullnode Go proxy, averaged over the last 1 minute. Clients → proxy → localhost node."
            level={rpsLevel(rpc.rps_1m)}
            accent
          />
          <RpcTile
            label="RPS 5m"
            value={num(rpc.rps_5m, 1)}
            hint="Same as RPS 1m, but averaged over the last 5 minutes — smoother view of sustained load."
          />
          <RpcTile
            label="Latency p50"
            value={`${num(rpc.latency_p50_ms, 0)} ms`}
            hint="Median (50th percentile) end-to-end time for a request in the Go proxy: accept → upstream fullnode → response."
            level={latencyLevel(rpc.latency_p50_ms)}
          />
          <RpcTile
            label="Latency p95"
            value={`${num(rpc.latency_p95_ms, 0)} ms`}
            hint="95th percentile proxy latency — 95% of requests were faster than this. Used for Telegram “RPC slow” alerts."
            level={latencyLevel(rpc.latency_p95_ms)}
            accent
          />
          <RpcTile
            label="In flight"
            value={num(rpc.in_flight, 0)}
            hint="How many RPC requests are currently being handled by the Go proxy right now (concurrency)."
          />
          <RpcTile
            label="Errors 5xx"
            value={num(rpc.errors_5xx, 0)}
            hint="Total HTTP 5xx responses from the Go proxy since process start (includes 502/503 and other server errors)."
            level={errLevel(rpc.errors_5xx)}
          />
        </SimpleGrid>
        <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="sm" mb="md">
          <RpcTile
            label="HTTP 502"
            value={num(rpc.http_502, 0)}
            hint="Bad Gateway — proxy could not get a valid response from the local fullnode (process down, timeout, connection reset)."
            level={errLevel(rpc.http_502)}
          />
          <RpcTile
            label="HTTP 503"
            value={num(rpc.http_503, 0)}
            hint="Service Unavailable — usually maintenance / RPC sleep during client update or node restart."
            level={errLevel(rpc.http_503)}
          />
          <RpcTile
            label="Upstream err"
            value={num(rpc.upstream_errors, 0)}
            hint="Failures dialing or talking to the localhost fullnode upstream (before a clean HTTP response)."
            level={errLevel(rpc.upstream_errors)}
          />
          <RpcTile
            label="4xx"
            value={num(rpc.errors_4xx, 0)}
            hint="HTTP 4xx from the proxy (bad request / not found / client errors). Usually client-side, not node health."
          />
        </SimpleGrid>
        <ChartCard
          title="Fullnode RPC load (RPS)"
          hint="Time series of requests/sec on the public Go RPC proxy (not host tip, not agent port)."
          data={rps}
          dataKey="rps"
          color="teal.5"
        />
      </Card>
      ) : null}
    </Stack>
  )
}

function DisksPanel({
  disks,
  history,
  host,
  hostIO,
  hostUtil,
  hasDisk,
}: {
  disks?: HostDiskIO[]
  history?: HostDiskIOHistory[]
  host: {
    readIops?: number
    writeIops?: number
    readMBs?: number
    writeMBs?: number
    utilPct?: number
    busy?: string
  }
  hostIO: Array<Record<string, string | number>>
  hostUtil: Array<Record<string, string | number>>
  hasDisk: boolean
}) {
  const items = disks?.filter((d) => d.name) || []
  const hottest = host.busy && items.some((d) => d.name === host.busy) ? host.busy : ''
  const defaultTab = hottest || (items.length === 1 ? items[0].name : 'all')
  const [tab, setTab] = useState<string | null>(null)
  const active = tab && (tab === 'all' || items.some((d) => d.name === tab)) ? tab : defaultTab
  const selected = active !== 'all' ? items.find((d) => d.name === active) : undefined
  const selectedHist = useMemo(
    () => (active !== 'all' ? history?.find((h) => h.name === active) : undefined),
    [active, history],
  )
  const io = selected
    ? chartPairSeries(selectedHist?.read_iops, selectedHist?.write_iops, 'read', 'write')
    : hostIO
  const util = selected ? chartSeries(selectedHist?.util, 'util') : hostUtil
  const readIops = selected?.read_iops ?? host.readIops
  const writeIops = selected?.write_iops ?? host.writeIops
  const readMBs = selected?.read_mb_s ?? host.readMBs
  const writeMBs = selected?.write_mb_s ?? host.writeMBs
  const utilPct = selected?.util_pct ?? host.utilPct
  const allFree = items.reduce((s, d) => s + (d.free_gb || 0), 0)
  const allTotal = items.reduce((s, d) => s + (d.total_gb || 0), 0)
  const freeGB = selected?.free_gb ?? (allTotal > 0 ? allFree : undefined)
  const totalGB = selected?.total_gb ?? (allTotal > 0 ? allTotal : undefined)
  const usedPct =
    selected?.used_pct ??
    (allTotal > 0 ? ((allTotal - allFree) / allTotal) * 100 : undefined)
  const mount = selected?.mount
  const label = selected?.name || (host.busy ? `hottest · ${host.busy}` : 'all disks')

  return (
    <Card padding="sm" className="metric-chart-panel" mb="md">
      {items.length > 1 ? (
        <Tabs value={active} onChange={setTab} mb="sm">
          <Tabs.List>
            <Tabs.Tab value="all">
              All
              {host.utilPct != null && !Number.isNaN(host.utilPct) ? (
                <Text span size="xs" c="dimmed" ml={6}>
                  {num(host.utilPct, 1)}%
                </Text>
              ) : null}
              {allFree > 0 ? (
                <Text span size="xs" c="dimmed" ml={6}>
                  {fmtDiskFree(allFree)} free
                </Text>
              ) : null}
            </Tabs.Tab>
            {items.map((d) => (
              <Tabs.Tab key={d.name} value={d.name}>
                {d.name}
                <Text
                  span
                  size="xs"
                  ml={6}
                  c={levelColor(diskUtilLevel(d.util_pct)) ?? 'dimmed'}
                >
                  {d.util_pct == null || Number.isNaN(d.util_pct) ? '—' : `${num(d.util_pct, 1)}%`}
                </Text>
                {d.free_gb != null && d.free_gb > 0 ? (
                  <Text span size="xs" c="dimmed" ml={6}>
                    {fmtDiskFree(d.free_gb)} free
                  </Text>
                ) : null}
              </Tabs.Tab>
            ))}
          </Tabs.List>
        </Tabs>
      ) : null}

      <DiskLoadBar
        pct={utilPct}
        busy={label}
        readIops={readIops}
        writeIops={writeIops}
        readMBs={readMBs}
        writeMBs={writeMBs}
        freeGB={freeGB}
        totalGB={totalGB}
        usedPct={usedPct}
        mount={mount}
        nested
      />

      <Grid>
        <Grid.Col span={{ base: 12, md: 6 }}>
          <PairChartCard
            title={selected ? `${selected.name} IOPS` : 'Host disk IOPS'}
            hint={
              selected
                ? `Reads/writes per second on ${selected.name} (/proc/diskstats).`
                : 'Sum of completed reads/writes per second on all whole physical disks.'
            }
            data={io}
            aKey="read"
            bKey="write"
            aColor="teal.5"
            bColor="orange.5"
            aLabel={`Read ${fmtIOPS(readIops)} · ${fmtDiskMBs(readMBs)}`}
            bLabel={`Write ${fmtIOPS(writeIops)} · ${fmtDiskMBs(writeMBs)}`}
            emptyHint={hasDisk ? 'Collecting disk samples…' : 'Disk I/O not ready yet (Update agent)'}
            bare
          />
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 6 }}>
          <ChartCard
            title={selected ? `${selected.name} load %` : 'Disk load %'}
            hint="iostat %util for this disk. All = hottest disk. 70% warn, 90%+ saturated."
            data={util}
            dataKey="util"
            color="pink.5"
            emptyHint={hasDisk ? 'Collecting…' : 'Disk util not ready yet'}
            bare
          />
        </Grid.Col>
      </Grid>
    </Card>
  )
}

function diskLoadColor(pct?: number): string {
  const level = diskUtilLevel(pct)
  if (level === 'critical') return 'red'
  if (level === 'warn') return 'yellow'
  return 'teal'
}

function diskUsedLevel(pct?: number): Level {
  if (pct == null || Number.isNaN(pct)) return undefined
  if (pct >= 95) return 'critical'
  if (pct >= 85) return 'warn'
  return 'ok'
}

function DiskLoadBar({
  pct,
  busy,
  readIops,
  writeIops,
  readMBs,
  writeMBs,
  freeGB,
  totalGB,
  usedPct,
  mount,
  nested,
}: {
  pct?: number
  busy?: string
  readIops?: number
  writeIops?: number
  readMBs?: number
  writeMBs?: number
  freeGB?: number
  totalGB?: number
  usedPct?: number
  mount?: string
  nested?: boolean
}) {
  const known = pct != null && !Number.isNaN(pct)
  const bar = known ? Math.max(0, Math.min(100, pct)) : 0
  const inner = (
    <>
      <Group justify="space-between" wrap="wrap" gap="sm" mb={6}>
        <Group gap={6} wrap="nowrap" align="center">
          <Text size="xs" c="dimmed" fw={600} tt="uppercase">
            Disk load
          </Text>
          <MetricHelp label="How busy this disk is right now (iostat %util). Same as Host CPU % — 100% means the disk queue is full. All = hottest disk." />
        </Group>
        <Group gap="md" wrap="wrap">
          <Text fw={700} size="xl" className="mono" c={known ? levelColor(diskUtilLevel(pct)) : 'dimmed'} style={{ fontVariantNumeric: 'tabular-nums', lineHeight: 1.2 }}>
            {known ? `${num(pct, 1)}%` : '—'}
          </Text>
          {busy ? (
            <Badge size="sm" variant="light" color="gray">
              {busy}
            </Badge>
          ) : null}
          <Text size="xs" c="dimmed" className="mono">
            R {fmtIOPS(readIops)} · W {fmtIOPS(writeIops)} · {fmtDiskMBs(readMBs)} / {fmtDiskMBs(writeMBs)}
          </Text>
          {freeGB != null && totalGB != null && totalGB > 0 ? (
            <Text size="sm" fw={600} className="mono" c={levelColor(diskUsedLevel(usedPct))}>
              {formatStorageGiB(freeGB, freeGB >= 1024 ? 1 : 0)} free
              <Text span size="xs" c="dimmed" fw={500}>
                {' '}
                / {formatStorageGiB(totalGB, totalGB >= 1024 ? 1 : 0)}
                {usedPct != null && !Number.isNaN(usedPct) ? ` · ${num(usedPct, 0)}% used` : ''}
                {mount ? ` · ${mount}` : ''}
              </Text>
            </Text>
          ) : null}
        </Group>
      </Group>
      <Progress
        value={bar}
        color={diskLoadColor(pct)}
        size="lg"
        radius="xl"
        animated={known && bar >= 70}
        striped={known && bar >= 70}
      />
      {usedPct != null && !Number.isNaN(usedPct) ? (
        <Progress
          value={Math.max(0, Math.min(100, usedPct))}
          color={diskUsedLevel(usedPct) === 'critical' ? 'red' : diskUsedLevel(usedPct) === 'warn' ? 'yellow' : 'gray'}
          size="sm"
          radius="xl"
          mt={6}
        />
      ) : null}
    </>
  )
  if (nested) return <div style={{ marginBottom: 12 }}>{inner}</div>
  return (
    <Card padding="sm" className="metric-chart-panel" mb="md">
      {inner}
    </Card>
  )
}

function MetricHelp({ label }: { label: string }) {
  return (
    <Tooltip label={label} multiline maw={280} withArrow position="top" openDelay={200}>
      <span className="metric-help" aria-label="What is this metric?">
        <IconHelp size={12} stroke={1.75} />
      </span>
    </Tooltip>
  )
}

function RpcTile({
  label,
  value,
  hint,
  level,
  accent,
}: {
  label: string
  value: string
  hint?: string
  level?: Level
  accent?: boolean
}) {
  const tile = (
    <div className={`rpc-metric-tile${accent ? ' rpc-metric-tile--accent' : ''}`}>
      <Group gap={4} wrap="nowrap" align="center" mb={2}>
        <Text size="xs" c="dimmed" tt="uppercase" fw={600} style={{ letterSpacing: 0.4 }}>
          {label}
        </Text>
        {hint ? <MetricHelp label={hint} /> : null}
      </Group>
      <Text
        fw={700}
        size={accent ? 'xl' : 'lg'}
        c={levelColor(level)}
        className="mono"
        style={{ fontVariantNumeric: 'tabular-nums', lineHeight: 1.25 }}
      >
        {value}
      </Text>
    </div>
  )
  if (!hint) return tile
  return (
    <Tooltip label={hint} multiline maw={280} withArrow position="top" openDelay={300}>
      {tile}
    </Tooltip>
  )
}

function Stat({
  label,
  value,
  hint,
  compact,
  level,
}: {
  label: string
  value: string
  hint?: string
  compact?: boolean
  level?: Level
}) {
  return (
    <div>
      <Group gap={4} wrap="nowrap" align="center">
        <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
          {label}
        </Text>
        {hint ? <MetricHelp label={hint} /> : null}
      </Group>
      <Text
        fw={700}
        size={compact ? 'sm' : 'lg'}
        c={levelColor(level) ?? (compact ? 'dimmed' : undefined)}
        style={{ fontVariantNumeric: 'tabular-nums' }}
      >
        {value}
      </Text>
    </div>
  )
}

function ChartCard({
  title,
  hint,
  data,
  dataKey,
  color,
  emptyHint,
  bare,
}: {
  title: string
  hint?: string
  data: Array<Record<string, string | number>>
  dataKey: string
  color: string
  emptyHint?: string
  bare?: boolean
}) {
  const inner = (
    <>
      <Group gap={4} wrap="nowrap" align="center" mb={6}>
        <Text size="xs" c="dimmed" fw={600}>
          {title}
        </Text>
        {hint ? <MetricHelp label={hint} /> : null}
      </Group>
      {data.length ? (
        <AreaChart
          h={140}
          data={data}
          dataKey="time"
          series={[{ name: dataKey, color }]}
          curveType="monotone"
          withDots={false}
          gridAxis="xy"
          tickLine="none"
          strokeWidth={2}
          fillOpacity={0.25}
        />
      ) : (
        <Text c="dimmed" size="sm" py="xl" ta="center">
          {emptyHint || 'Collecting samples…'}
        </Text>
      )}
    </>
  )
  if (bare) return inner
  return (
    <Card padding="sm" className="metric-chart-panel" h="100%">
      {inner}
    </Card>
  )
}

function PairChartCard({
  title,
  hint,
  data,
  aKey,
  bKey,
  aColor,
  bColor,
  aLabel,
  bLabel,
  emptyHint,
  bare,
}: {
  title: string
  hint?: string
  data: Array<Record<string, string | number>>
  aKey: string
  bKey: string
  aColor: string
  bColor: string
  aLabel: string
  bLabel: string
  emptyHint?: string
  bare?: boolean
}) {
  const inner = (
    <>
      <Group gap={4} wrap="wrap" align="center" mb={6}>
        <Text size="xs" c="dimmed" fw={600}>
          {title}
        </Text>
        {hint ? <MetricHelp label={hint} /> : null}
        <Badge size="xs" variant="light" color="teal">
          {aLabel}
        </Badge>
        <Badge size="xs" variant="light" color="orange">
          {bLabel}
        </Badge>
      </Group>
      {data.length ? (
        <AreaChart
          h={140}
          data={data}
          dataKey="time"
          series={[
            { name: aKey, color: aColor, label: 'Read' },
            { name: bKey, color: bColor, label: 'Write' },
          ]}
          curveType="monotone"
          withDots={false}
          gridAxis="xy"
          tickLine="none"
          strokeWidth={2}
          fillOpacity={0.18}
          withLegend
          legendProps={{ verticalAlign: 'top', height: 20 }}
        />
      ) : (
        <Text c="dimmed" size="sm" py="md" ta="center">
          {emptyHint || 'Collecting samples…'}
        </Text>
      )}
    </>
  )
  if (bare) return inner
  return (
    <Card padding="sm" className="metric-chart-panel" h="100%">
      {inner}
    </Card>
  )
}

function NetChartCard({
  title,
  hint,
  data,
  rxLabel,
  txLabel,
  emptyHint,
}: {
  title: string
  hint?: string
  data: Array<{ time: string; rx: number; tx: number }>
  rxLabel: string
  txLabel: string
  emptyHint?: string
}) {
  return (
    <Card padding="sm" className="metric-chart-panel" h="100%">
      <Group gap={4} wrap="wrap" align="center" mb={6}>
        <Text size="xs" c="dimmed" fw={600}>
          {title}
        </Text>
        {hint ? <MetricHelp label={hint} /> : null}
        <Badge size="xs" variant="light" color="teal">
          {rxLabel}
        </Badge>
        <Badge size="xs" variant="light" color="cyan">
          {txLabel}
        </Badge>
      </Group>
      {data.length ? (
        <AreaChart
          h={140}
          data={data}
          dataKey="time"
          series={[
            { name: 'rx', color: 'teal.5', label: 'RX' },
            { name: 'tx', color: 'cyan.5', label: 'TX' },
          ]}
          curveType="monotone"
          withDots={false}
          gridAxis="xy"
          tickLine="none"
          strokeWidth={2}
          fillOpacity={0.18}
          withLegend
          legendProps={{ verticalAlign: 'top', height: 20 }}
        />
      ) : (
        <Text c="dimmed" size="sm" py="md" ta="center">
          {emptyHint || 'Collecting network samples…'}
        </Text>
      )}
    </Card>
  )
}
