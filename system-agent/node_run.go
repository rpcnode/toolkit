package main

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// node-run.json — operator Stop/Start truth for every network.
// Written only after a successful stop or start. Pipeline must not auto-start
// while status=stopped. UI Stop/Start + client update read this file.
//
// Path: /var/lib/rpcnode/<network>-<env>/node-run.json

const nodeRunFileName = "node-run.json"

type nodeRunState struct {
	Status    string `json:"status"`
	Source    string `json:"source"`
	UpdatedAt string `json:"updated_at"`
}

func nodeRunPath(cfg Config) string {
	if v := strings.TrimSpace(os.Getenv("RPCNODE_NODE_RUN_STATE")); v != "" {
		return v
	}
	dir := filepath.Dir(strings.TrimSpace(cfg.StateFile))
	if dir == "" || dir == "." {
		net := strings.ToLower(strings.TrimSpace(cfg.Network))
		env := strings.ToLower(strings.TrimSpace(cfg.Env))
		if net == "" {
			net = "unknown"
		}
		if env == "" {
			env = "mainnet"
		}
		dir = filepath.Join("/var/lib/rpcnode", net+"-"+env)
	}
	return filepath.Join(dir, nodeRunFileName)
}

func loadNodeRun(cfg Config) nodeRunState {
	doc := readJSONFile(nodeRunPath(cfg))
	return nodeRunState{
		Status:    strings.ToLower(jsonString(doc["status"])),
		Source:    jsonString(doc["source"]),
		UpdatedAt: jsonString(doc["updated_at"]),
	}
}

func jsonString(v any) string {
	if v == nil {
		return ""
	}
	s := strings.TrimSpace(fmt.Sprint(v))
	if s == "<nil>" {
		return ""
	}
	return s
}

func operatorNodeStopped(cfg Config) bool {
	return loadNodeRun(cfg).Status == "stopped"
}

func saveNodeRun(cfg Config, status, source string) error {
	status = strings.ToLower(strings.TrimSpace(status))
	if status != "stopped" && status != "running" {
		return fmt.Errorf("node-run status %q", status)
	}
	path := nodeRunPath(cfg)
	if err := ensureDir(filepath.Dir(path)); err != nil {
		return err
	}
	return writeJSONFile(path, nodeRunState{
		Status:    status,
		Source:    strings.TrimSpace(source),
		UpdatedAt: time.Now().UTC().Format(time.RFC3339),
	})
}

func nodeRunSnapshot(cfg Config) map[string]any {
	st := loadNodeRun(cfg)
	if st.Status == "" {
		return map[string]any{"status": "", "file": nodeRunPath(cfg)}
	}
	return map[string]any{
		"status":     st.Status,
		"source":     st.Source,
		"updated_at": st.UpdatedAt,
		"file":       nodeRunPath(cfg),
	}
}
