package main

import (
	"context"
	"fmt"
	"log"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
)

// stopNodeUnits — CLI/RPC graceful stop then systemctl stop (same units as restart).
func stopNodeUnits(cfg Config, budget time.Duration) error {
	gracefulClientStop(cfg)
	units := cfgNodeUnits(cfg)
	if len(units) == 0 {
		return fmt.Errorf("empty unit")
	}
	for _, u := range units {
		done := make(chan struct{})
		go func(unit string) {
			_ = exec.Command("systemctl", "stop", unit+".service").Run()
			close(done)
		}(u)
		select {
		case <-done:
		case <-time.After(budget):
			log.Printf("client_update: stop %s exceeded %s — waiting for inactive", u, budget)
		}
		if err := waitUnitStopped(u, budget); err != nil {
			return fmt.Errorf("%s: %w", u, err)
		}
	}
	return nil
}

func startNodeUnits(cfg Config) error {
	for _, u := range cfgNodeUnits(cfg) {
		out, err := exec.Command("systemctl", "start", u+".service").CombinedOutput()
		if err != nil {
			return fmt.Errorf("systemctl start %s: %v (%s)", u, err, strings.TrimSpace(string(out)))
		}
	}
	return nil
}

// gracefulClientStop — network CLI/RPC stop (same idea as api-agent gracefulStopNode),
// then systemctl stop. Never SIGKILL here.
func gracefulClientStop(cfg Config) {
	net := strings.ToLower(strings.TrimSpace(cfg.Network))
	prof := LookupNetworkProfile(cfg.Network, cfg.Env)
	etc := strings.TrimSpace(cfg.EtcDir)
	if etc == "" {
		etc = prof.EtcPath
	}
	opt := strings.TrimSpace(cfg.OptDir)
	if opt == "" {
		opt = prof.OptPath
	}

	switch net {
	case "bitcoin":
		runClientCLIStop(findClientTool(opt, "bitcoin-cli"), filepath.Join(etc, "bitcoin.conf"))
	case "doge":
		runClientCLIStop(findClientTool(opt, "dogecoin-cli"), filepath.Join(etc, "dogecoin.conf"))
	case "ltc":
		runClientCLIStop(findClientTool(opt, "litecoin-cli"), filepath.Join(etc, "litecoin.conf"))
	case "dash":
		runClientCLIStop(findClientTool(opt, "dash-cli"), filepath.Join(etc, "dash.conf"))
	case "bch":
		cli := findClientTool(opt, "bitcoin-cli")
		if cli == "" {
			cli = findClientTool(opt, "bitcoin-cash-cli")
		}
		runClientCLIStop(cli, filepath.Join(etc, "bitcoin.conf"))
	case "xrpl":
		runXRPLServerStop(opt, etc)
	}
}

func findClientTool(opt, name string) string {
	for _, p := range []string{
		filepath.Join(opt, "bin", name),
		filepath.Join(opt, name),
		"/usr/local/bin/" + name,
		"/usr/bin/" + name,
	} {
		if fileExists(p) {
			return p
		}
	}
	if p, err := exec.LookPath(name); err == nil {
		return p
	}
	return ""
}

func runClientCLIStop(cli, conf string) {
	cli = strings.TrimSpace(cli)
	conf = strings.TrimSpace(conf)
	if cli == "" || !fileExists(cli) || conf == "" || !fileExists(conf) {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	_ = exec.CommandContext(ctx, cli, "-conf="+conf, "stop").Run()
}

func runXRPLServerStop(opt, etc string) {
	conf := filepath.Join(etc, "xrpld.cfg")
	if !fileExists(conf) {
		conf = filepath.Join(etc, "rippled.cfg")
	}
	bin := findClientTool(opt, "xrpld")
	if bin == "" {
		bin = findClientTool(opt, "rippled")
	}
	if bin == "" {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	if fileExists(conf) {
		_ = exec.CommandContext(ctx, bin, "--conf", conf, "server_stop").Run()
		return
	}
	_ = exec.CommandContext(ctx, bin, "server_stop").Run()
}
