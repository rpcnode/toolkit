package main

import (
	"strings"
)

type catalogPortRole struct {
	Port     int
	Role     string
	Label    string
	External bool // Public / Agent / P2P — panel reach-probes these
}

func catalogPortRoles(prof networkPortProfile) []catalogPortRole {
	return []catalogPortRole{
		{prof.Public, "public_port", "Go RPC (proxy)", true},
		{prof.Agent, "agent_port", "Node Agent API", true},
		{prof.NodeHTTP, "node_http_port", "Upstream HTTP / RPC", false},
		{prof.P2P, "p2p_port", "P2P", true},
		{prof.SolHTTP, solHTTPRole(prof), solHTTPLabel(prof), false},
		{prof.PBFTHTTP, "pbft_http_port", "PBFT HTTP (reserved)", false},
		{prof.GRPC, "grpc_port", "gRPC", false},
		{prof.GRPCSol, "grpc_sol_port", "gRPC solidity", false},
		{prof.GRPCPbft, "grpc_pbft_port", "gRPC PBFT", false},
		{prof.Metrics, "metrics_port", "Metrics", false},
		{prof.ZMQRawBlock, "zmq_rawblock", "ZMQ rawblock", false},
		{prof.ZMQRawTx, "zmq_rawtx", "ZMQ rawtx", false},
	}
}

func solHTTPRole(prof networkPortProfile) string {
	if strings.EqualFold(prof.Network, "stellar") {
		return "captive_core_http_query_port"
	}
	return "sol_http_port"
}

func solHTTPLabel(prof networkPortProfile) string {
	if strings.EqualFold(prof.Network, "stellar") {
		return "Captive Core HTTP_QUERY"
	}
	return "Solidity HTTP (reserved)"
}

func catalogExternalPorts(prof networkPortProfile) []int {
	var out []int
	for _, r := range catalogPortRoles(prof) {
		if r.External && r.Port > 0 {
			out = append(out, r.Port)
		}
	}
	return out
}

func buildCheckedPorts(network, env string) (checked []map[string]any, busy []map[string]any) {
	prof := lookupPortProfile(network, env)
	for _, r := range catalogPortRoles(prof) {
		if r.Port <= 0 {
			continue
		}
		holder := portBusyHolder(r.Port, network, env)
		bind := "free"
		if holder != "" {
			bind = "busy"
		}
		entry := map[string]any{
			"port":     r.Port,
			"role":     r.Role,
			"label":    r.Label,
			"external": r.External,
			"bind":     bind,
		}
		if holder != "" {
			entry["holder"] = holder
			info := inspectPortHolder(r.Port, network, env)
			if info.PID != "" {
				entry["pid"] = info.PID
			}
			if info.Comm != "" {
				entry["comm"] = info.Comm
			}
			if info.Cmdline != "" {
				entry["cmdline"] = info.Cmdline
			}
			if info.Unit != "" {
				entry["unit"] = info.Unit
			}
			entry["killable"] = info.Killable
			if info.KillBlocked != "" {
				entry["kill_blocked"] = info.KillBlocked
			}
			busy = append(busy, entry)
		}
		checked = append(checked, entry)
	}
	if busy == nil {
		busy = []map[string]any{}
	}
	if checked == nil {
		checked = []map[string]any{}
	}
	return checked, busy
}
