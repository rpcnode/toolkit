package main

import (
	"fmt"
	"strings"
	"sync"

	"github.com/ali3/tron-toolkit/panel/store"
)

// In-memory edge dedup for collector → Telegram (survives process only).
var notifyEdgePrev sync.Map // key → value

func notifyEdge(db *store.DB, dbPath, edgeKey, value, eventType, message string) {
	value = strings.TrimSpace(value)
	if value == "" || eventType == "" {
		return
	}
	prev, loaded := notifyEdgePrev.Load(edgeKey)
	notifyEdgePrev.Store(edgeKey, value)
	if loaded {
		if prevStr, ok := prev.(string); ok && prevStr == value {
			return
		}
	} else {
		// First sight of "healthy" / current — don't spam on collector boot.
		if value == "up" || value == "ok" || value == "ready" || value == "current" || value == "working" {
			return
		}
	}
	// Recovery transitions only for "up"/"ok" when previous was bad.
	if value == "up" || value == "ok" || value == "ready" || value == "working" {
		prevStr, _ := prev.(string)
		if prevStr != "down" && prevStr != "low" && prevStr != "error" && prevStr != "outdated" {
			return
		}
	}
	panelNotifySend(db, dbPath, eventType, message)
}

func (c *collector) notifyObserveNode(
	prevNode store.Node,
	prevStatus store.NodeStatus,
	node store.Node,
	st store.NodeStatus,
	unreachable bool,
) {
	dbPath := c.dbPath()
	t := c.targetForNode(node)

	// node.down / node.up — only after continuous hold (skip single poll timeouts).
	downKey := "node|" + node.ID + "|reach"
	bad := unreachable || strings.EqualFold(st.Health, "error") ||
		strings.EqualFold(node.Status, "agent_error")
	cfg, _ := loadTelegramNotifySettings(c.db)
	th := mergeThresholds(&cfg.Thresholds)
	switch observeReachHold(downKey, bad, th.nodeDownHold(), th.nodeUpHold()) {
	case "down":
		notifyEdge(c.db, dbPath, downKey, "down", subNodeDown,
			formatNotifyAlert(subNodeDown, t, "Node unreachable / RPC unhealthy",
				firstNonEmptyStr(st.Error, st.Detail, "agent unreachable")))
	case "up":
		notifyEdge(c.db, dbPath, downKey, "up", subNodeUp,
			formatNotifyAlert(subNodeUp, t, "Node recovered", "RPC / agent recovered"))
	}

	// lifecycle.step / error / ready — only on real transitions (skip cold seed except error).
	phase := strings.ToLower(strings.TrimSpace(st.Phase))
	prevPhase := strings.ToLower(strings.TrimSpace(prevStatus.Phase))
	if phase != "" && phase != prevPhase {
		stepKey := "node|" + node.ID + "|phase"
		if prevPhase == "" && phase != "error" && phase != "failed" {
			if phase == "working" || phase == "run" || phase == "healthy" {
				notifyEdgePrev.Store(stepKey, "ready:"+phase)
			} else {
				notifyEdgePrev.Store(stepKey, phase)
			}
		} else {
			switch {
			case phase == "error" || phase == "failed":
				notifyEdge(c.db, dbPath, stepKey, "error:"+phase, subLifecycleError,
					formatNotifyAlert(subLifecycleError, t, "Lifecycle error",
						firstNonEmptyStr(st.Detail, st.Label, phase)))
			case phase == "working" || phase == "run" || phase == "healthy":
				notifyEdge(c.db, dbPath, stepKey, "ready:"+phase, subLifecycleReady,
					formatNotifyAlert(subLifecycleReady, t, "Node ready (working)",
						"phase: "+phase))
			default:
				notifyEdge(c.db, dbPath, stepKey, phase, subLifecycleStep,
					formatNotifyAlert(subLifecycleStep, t, "Lifecycle step changed",
						fmt.Sprintf("%s → %s", emptyAs(prevPhase, "?"), phase),
						firstNonEmptyStr(st.Detail, st.Label)))
			}
		}
	}

	// client.update_available — seed without spam if already flagged in SQLite before this process.
	clientKey := "node|" + node.ID + "|client"
	if node.ClientUpdateAvailable {
		val := node.ClientLatest
		if val == "" {
			val = "available"
		}
		if !prevNode.ClientUpdateAvailable || prevNode.ClientLatest != node.ClientLatest {
			notifyEdge(c.db, dbPath, clientKey, val, subClientUpdate,
				formatNotifyAlert(subClientUpdate, t, "Client update available",
					fmt.Sprintf("%s → %s",
						emptyAs(node.ClientVersion, "?"),
						emptyAs(node.ClientLatest, "?"))))
		} else {
			notifyEdgePrev.Store(clientKey, val)
		}
	} else {
		notifyEdgePrev.Store(clientKey, "current")
	}
}

func (c *collector) notifyObserveRPC(node store.Node, rp store.RPCProxyStats) {
	dbPath := c.dbPath()
	st, err := loadTelegramNotifySettings(c.db)
	if err != nil {
		return
	}
	th := mergeThresholds(&st.Thresholds)
	t := c.targetForNode(node)

	// rpc.rps_high — fullnode Go proxy requests/sec (1m window).
	rpsKey := "node|" + node.ID + "|rpc_rps"
	rpsThresh := th.RPCRPS
	if rpsThresh <= 0 {
		rpsThresh = defaultRPCRPS
	}
	if rp.RPS1m >= rpsThresh {
		notifyEdge(c.db, dbPath, rpsKey, fmt.Sprintf("%.0f", rpsThresh), subRPCRPSHigh,
			formatNotifyAlert(subRPCRPSHigh, t, "Fullnode RPC RPS high",
				fmt.Sprintf("rps_1m: %s (threshold %s)", formatDiskPct(rp.RPS1m), formatDiskPct(rpsThresh)),
				fmt.Sprintf("p95: %s ms · inflight: %d · rps_5m: %s",
					formatDiskPct(rp.LatencyP95Ms), rp.InFlight, formatDiskPct(rp.RPS5m))))
	} else {
		notifyEdgePrev.Store(rpsKey, "ok")
	}

	// rpc.slow — latency p95 (need some traffic so idle nodes don't alert).
	slowKey := "node|" + node.ID + "|rpc_slow"
	if rp.LatencyP95Ms >= th.RPCLatencyP95Ms && (rp.RPS1m >= 0.5 || rp.InFlight > 0 || rp.Total > 0) {
		notifyEdge(c.db, dbPath, slowKey, fmt.Sprintf("%.0f", th.RPCLatencyP95Ms), subRPCSlow,
			formatNotifyAlert(subRPCSlow, t, "Fullnode RPC slow (p95)",
				fmt.Sprintf("p95: %s ms (threshold %s ms)", formatDiskPct(rp.LatencyP95Ms), formatDiskPct(th.RPCLatencyP95Ms)),
				fmt.Sprintf("rps_1m: %s · inflight: %d", formatDiskPct(rp.RPS1m), rp.InFlight)))
	} else {
		notifyEdgePrev.Store(slowKey, "ok")
	}

	// rpc.errors — error rate between polls (5xx + upstream vs total delta).
	errKey := "node|" + node.ID + "|rpc_err"
	prevKey := "node|" + node.ID + "|rpc_counters"
	curCounters := fmt.Sprintf("%d|%d|%d", rp.Total, rp.Errors5xx, rp.UpstreamErrors)
	if prev, ok := notifyEdgePrev.Load(prevKey); ok {
		prevStr, _ := prev.(string)
		var prevTotal, prev5xx, prevUp int64
		fmt.Sscanf(prevStr, "%d|%d|%d", &prevTotal, &prev5xx, &prevUp)
		dTotal := rp.Total - prevTotal
		dErr := (rp.Errors5xx - prev5xx) + (rp.UpstreamErrors - prevUp)
		if dTotal < 0 {
			dTotal = rp.Total
			dErr = rp.Errors5xx + rp.UpstreamErrors
		}
		if dTotal >= int64(minRPCErrorSample) && dErr > 0 {
			rate := 100.0 * float64(dErr) / float64(dTotal)
			if rate >= th.RPCErrorRatePct {
				notifyEdge(c.db, dbPath, errKey, fmt.Sprintf("%.1f", th.RPCErrorRatePct), subRPCErrors,
					formatNotifyAlert(subRPCErrors, t, "Fullnode RPC error rate high",
						fmt.Sprintf("error rate: %.1f%% over %d req (threshold %.1f%%)", rate, dTotal, th.RPCErrorRatePct),
						fmt.Sprintf("rps_1m: %s · 5xx: %d · upstream: %d",
							formatDiskPct(rp.RPS1m), rp.Errors5xx, rp.UpstreamErrors)))
			} else {
				notifyEdgePrev.Store(errKey, "ok")
			}
		}
	}
	notifyEdgePrev.Store(prevKey, curCounters)
}

func (c *collector) notifyObserveServer(srv store.Server, m store.ServerMetrics) {
	dbPath := c.dbPath()
	t := c.targetForServer(srv)
	cfg, _ := loadTelegramNotifySettings(c.db)
	th := mergeThresholds(&cfg.Thresholds)
	diskThresh := th.DiskUsedPct
	if diskThresh <= 0 {
		diskThresh = defaultDiskUsedPct
	}
	cpuThresh := th.CPUHighPct
	if cpuThresh <= 0 {
		cpuThresh = defaultCPUHighPct
	}

	// disk.low
	diskKey := "server|" + srv.ID + "|disk"
	if m.DiskUsedPct >= diskThresh {
		notifyEdge(c.db, dbPath, diskKey, fmt.Sprintf("%.0f", diskThresh), subDiskLow,
			formatNotifyAlert(subDiskLow, t, "Disk space low",
				fmt.Sprintf("used: %s%% (threshold %s%%)", formatDiskPct(m.DiskUsedPct), formatDiskPct(diskThresh))))
	} else if m.DiskUsedPct > 0 {
		notifyEdgePrev.Store(diskKey, "ok")
	}

	// cpu.high — host CPU busy % only (/proc/stat). load_pct is informational, not the trigger.
	cpuKey := "server|" + srv.ID + "|cpu"
	cpuPct := m.CPUPct
	loadPct := m.LoadPct
	if cpuPct >= cpuThresh {
		detail := fmt.Sprintf("cpu: %s%% (threshold %s%%)", formatDiskPct(cpuPct), formatDiskPct(cpuThresh))
		if loadPct > 0 {
			detail += fmt.Sprintf("\nload: %s%%", formatDiskPct(loadPct))
		}
		notifyEdge(c.db, dbPath, cpuKey, fmt.Sprintf("%.0f", cpuThresh), subCPUHigh,
			formatNotifyAlert(subCPUHigh, t, "CPU high", detail))
	} else if cpuPct > 0 {
		notifyEdgePrev.Store(cpuKey, "ok")
	}

	// agent.update_available
	latest := getCDNToolkitVersion(false)
	agentKey := "server|" + srv.ID + "|agent"
	if agentVersionOutdated(srv.AgentVersion, latest) {
		notifyEdge(c.db, dbPath, agentKey, latest, subAgentUpdate,
			formatNotifyAlert(subAgentUpdate, t, "Agent update available",
				fmt.Sprintf("%s → %s", emptyAs(srv.AgentVersion, "?"), latest)))
	} else if strings.TrimSpace(srv.AgentVersion) != "" {
		notifyEdgePrev.Store(agentKey, "current")
	}
}

func (c *collector) dbPath() string {
	// store.DB doesn't expose path; collector opened with PANEL_DB — use env/default.
	return envOr("PANEL_DB", "/var/lib/rpcnode/panel.db")
}

func firstNonEmptyStr(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func emptyAs(s, def string) string {
	if strings.TrimSpace(s) == "" {
		return def
	}
	return strings.TrimSpace(s)
}
