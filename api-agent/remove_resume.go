package main

import (
	"encoding/json"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"time"
)

var removeJobsDir = "/var/lib/rpcnode/remove-jobs"

// scheduleRemoveJobResumeOnStartup — tip only. If Update/kill interrupted async
// wipe, jobs stay at deleting/started/wiped and leaf restart recreates /data.
// Resume finishes wipe + teardown so /data/<network>/<env> (and empty parents) go away.
func scheduleRemoveJobResumeOnStartup() {
	go func() {
		time.Sleep(4 * time.Second)
		if !isHostTipProcess() {
			return
		}
		for _, s := range resumeStuckRemoveJobs() {
			log.Printf("remove-resume: %s", s)
		}
	}()
}

func resumeStuckRemoveJobs() []string {
	entries, err := os.ReadDir(removeJobsDir)
	if err != nil {
		return nil
	}
	var steps []string
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		path := filepath.Join(removeJobsDir, e.Name())
		b, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		var doc map[string]any
		if json.Unmarshal(b, &doc) != nil {
			continue
		}
		network, _ := doc["network"].(string)
		env, _ := doc["env"].(string)
		status, _ := doc["status"].(string)
		network = normalizeNetwork(network)
		env = normalizeEnv(env)
		if network == "" || env == "" {
			network, env = splitNodesFileName(e.Name())
		}
		if !removeJobShouldResume(status, network, env) {
			continue
		}
		wantWipe := removeJobDeleteFiles(network, env)
		steps = append(steps, fmt.Sprintf("resuming %s/%s (was %s; delete_files=%v)", network, env, status, wantWipe))
		// delay=0: tip-driven; kill already done → teardown units → wipe if requested.
		runRemoveAfterACK(network, env, wantWipe, 0)
		steps = append(steps, fmt.Sprintf("finished resume %s/%s", network, env))
	}
	return steps
}

func removeJobShouldResume(status, network, env string) bool {
	switch strings.ToLower(strings.TrimSpace(status)) {
	case "deleting", "started", "wiped":
		// Interrupted tip mid-wipe. Re-provision must call clearRemoveJobOnProvision.
		return true
	case "error":
		for _, p := range nodeDataPaths(network, env) {
			if _, err := os.Stat(p); err == nil {
				return true
			}
		}
		return false
	case "aborted_heal", "completed", "superseded":
		return false
	default:
		return false
	}
}

// clearRemoveJobOnProvision — Confirm ports / re-provision supersedes a stuck wipe.
func clearRemoveJobOnProvision(network, env string) {
	path := removeJobPath(network, env)
	b, err := os.ReadFile(path)
	if err != nil {
		return
	}
	var doc map[string]any
	if json.Unmarshal(b, &doc) != nil {
		return
	}
	st, _ := doc["status"].(string)
	switch strings.ToLower(strings.TrimSpace(st)) {
	case "deleting", "started", "wiped", "error", "aborted_heal", "completed":
		writeRemoveJob(network, env, "superseded", "cleared by provision", nil)
	}
}

// pruneEmptyNetworkParents — after /data/net/env is gone, drop empty /data/net
// (same for /etc /opt /var/log) so the network folder does not linger.
func pruneEmptyNetworkParents(network, env string) (steps []string) {
	network = normalizeNetwork(network)
	env = normalizeEnv(env)
	if network == "" || env == "" {
		return nil
	}
	parents := []string{
		filepath.Join("/data", network),
		filepath.Join("/etc", network),
		filepath.Join("/opt", network),
		filepath.Join("/var/log", network),
	}
	for _, parent := range parents {
		if err := removeDirIfEmpty(parent); err == nil {
			steps = append(steps, "removed empty "+parent)
		}
	}
	return steps
}

func removeDirIfEmpty(dir string) error {
	st, err := os.Stat(dir)
	if err != nil || !st.IsDir() {
		return err
	}
	ents, err := os.ReadDir(dir)
	if err != nil {
		return err
	}
	if len(ents) > 0 {
		return fmt.Errorf("not empty")
	}
	return os.Remove(dir)
}
