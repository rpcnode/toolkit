import type { ReactNode } from 'react'
import { Badge, Card, Grid, Group, SimpleGrid, Stack, Text, Title, Tooltip } from '@mantine/core'
import { AreaChart } from '@mantine/charts'
import { IconHelp } from '@tabler/icons-react'
import type { MetricsPayload, StatusPayload } from '../types'
import { chartNetSeries, chartSeries, fmtBytesGiB, fmtMbps, num } from '../lib/format'

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
  const hasNode = cur?.node_net_rx_mbps != null || cur?.node_cpu_pct != null || cur?.node_mem_pct != null

  const hostCard = (
    <Card>
      <Group justify="space-between" mb="md" wrap="wrap" gap="sm">
        <div>
          <Title order={3}>Host & node</Title>
          <Text c="dimmed" size="xs">
            Host NIC/CPU/RAM · this node unit (cgroup accounting)
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
}: {
  label: string
  value: string
  hint?: string
  compact?: boolean
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
        c={compact ? 'dimmed' : undefined}
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
}: {
  title: string
  hint?: string
  data: Array<Record<string, string | number>>
  dataKey: string
  color: string
  emptyHint?: string
}) {
  return (
    <Card padding="sm" className="metric-chart-panel" h="100%">
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
