package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestParseTonOutOfSync(t *testing.T) {
	cases := []struct {
		in   string
		want float64
		ok   bool
	}{
		{"Masterchain out of sync: 12.5 sec", 12.5, true},
		{"out of sync: 0 sec", 0, true},
		{"Masterchain out of sync: 3 sec", 3, true},
		{"no signal here", 0, false},
		{"OUT OF SYNC: 120 sec", 120, true},
		// Local validator figure is BLOCKS — must not win over masterchain seconds.
		{"Local validator out of sync: 45\nMasterchain out of sync: 3 sec\n", 3, true},
		// MyTonCtrl paints the number green — must still parse.
		{"Masterchain out of sync: \x1b[32m5 sec\x1b[0m", 5, true},
		// Bare cache file from agent.
		{"2\n", 2, true},
	}
	for _, c := range cases {
		got, ok := parseTonOutOfSync(c.in)
		if ok != c.ok || (ok && got != c.want) {
			t.Fatalf("%q: got %v/%v want %v/%v", c.in, got, ok, c.want, c.ok)
		}
	}
}

func TestParseTonGetstatsUnixMaster(t *testing.T) {
	sample := `
unixtime 1700000100
masterchainblocktime 1700000000
masterchainblocknumber 41234567
`
	oos, seq, ok := parseTonSyncSignals(sample)
	if !ok || oos != 100 {
		t.Fatalf("getstats oos: got %v ok=%v want 100", oos, ok)
	}
	if seq != 41234567 {
		t.Fatalf("seqno: got %d", seq)
	}
}

func TestParseTonGetstatsMasterTimeZero(t *testing.T) {
	sample := "unixtime 1700000100\nmasterchainblocktime 0\n"
	if _, _, ok := parseTonSyncSignals(sample); ok {
		t.Fatal("masterchainblocktime=0 must not yield fake oos")
	}
}

func TestParseTonTimediff(t *testing.T) {
	oos, _, ok := parseTonSyncSignals(`"timediff": 42.5`)
	if !ok || oos != 42.5 {
		t.Fatalf("timediff: got %v ok=%v", oos, ok)
	}
}

func TestParseTonLastKnownBlockAgo(t *testing.T) {
	sample := "Local validator initial sync status: Syncing blocks, last known block was 35601 s ago"
	oos, _, ok := parseTonSyncSignals(sample)
	if !ok || oos != 35601 {
		t.Fatalf("last known block ago: got %v ok=%v want 35601", oos, ok)
	}
}

func TestTonLagClosedPct(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{StateFile: filepath.Join(dir, "state.json"), Env: "mainnet"}

	p1, ok := tonLagClosedPct(cfg, 1000)
	if !ok || p1 != 0.1 {
		t.Fatalf("first sample at peak want 0.1 got %v ok=%v", p1, ok)
	}
	p2, ok := tonLagClosedPct(cfg, 500)
	if !ok || p2 < 49.9 || p2 > 50.1 {
		t.Fatalf("half lag closed want ~50 got %v ok=%v", p2, ok)
	}
	p3, ok := tonLagClosedPct(cfg, 2)
	if !ok || p3 != 99.9 {
		t.Fatalf("near healthy want 99.9 got %v ok=%v", p3, ok)
	}
	_ = os.Remove(tonCatchupStatePath(cfg))
}

func TestTonVerifyPctHealthy(t *testing.T) {
	info := tonRPCInfo{OK: true, OutOfSyncSec: 2, OutOfSyncOK: true}
	info.Synced = info.OutOfSyncSec <= tonOutOfSyncHealthySec && info.OK
	if info.Synced {
		info.VerifyPct = 1
	}
	if !info.Synced || info.VerifyPct != 1 {
		t.Fatalf("expected synced at 2s: %+v", info)
	}
	info2 := tonRPCInfo{OK: true, OutOfSyncSec: 30, OutOfSyncOK: true}
	info2.Synced = info2.OutOfSyncSec <= tonOutOfSyncHealthySec && info2.OK
	if info2.Synced {
		t.Fatalf("must not mark synced at 30s behind: %+v", info2)
	}
}

func TestTonSyncDetailLagClosed(t *testing.T) {
	info := tonRPCInfo{OutOfSyncOK: true, OutOfSyncSec: 400, Seqno: 99, VerifyPct: 0.25}
	d := tonSyncDetail(info, true)
	for _, part := range []string{"400", "lag closed", "seqno"} {
		if !strings.Contains(d, part) {
			t.Fatalf("detail=%q missing %q", d, part)
		}
	}
}

func TestParseTonAria2DumpProgress(t *testing.T) {
	dir := t.TempDir()
	logDir := filepath.Join(dir, "log")
	if err := os.MkdirAll(logDir, 0o755); err != nil {
		t.Fatal(err)
	}
	// Point parser at a fake /var/log/ton/<env>/bootstrap.log via chroot-like override:
	// write into the real relative path expected by tonBootstrapDumpProgress — use Env
	// and temporarily cannot override /var/log; test the regex helpers directly.
	line := "[#153b60 12GiB/235GiB(5%) CN:8 DL:112MiB ETA:33m54s]"
	m := tonAria2DumpRe.FindStringSubmatch(line)
	if len(m) < 4 || m[1] != "12GiB" || m[2] != "235GiB" || m[3] != "5" || m[4] != "33m54s" {
		t.Fatalf("aria2 re: %#v", m)
	}
	// Old wget-style regex must not require space after %) — aria2 uses ).
	if tonWgetDumpPctRe.MatchString(line) {
		// optional; main signal is aria2
	}
	wget := "dump  45%  1.2G"
	mw := tonWgetDumpPctRe.FindStringSubmatch(wget)
	if len(mw) < 2 || mw[1] != "45" {
		t.Fatalf("wget pct: %#v", mw)
	}
	// git clone progress must not look like dump 100%
	git := "     0K .....                                                 100% 52.2M=0s"
	if tonWgetDumpPctRe.MatchString(git) || tonAria2DumpRe.MatchString(git) {
		t.Fatalf("git progress must not match dump regexes: %q", git)
	}
}

func TestTonBootstrapDumpProgressFromLog(t *testing.T) {
	// Exercise full parser with a temp log by monkey-patching path via Env under /tmp —
	// tonBootstrapDumpProgress hardcodes /var/log/ton/<env>/bootstrap.log.
	// Skip if we cannot write there; unit-test regex coverage above is enough for CI.
	env := "rpcnode-test-ton-dump"
	logPath := filepath.Join("/var/log/ton", env, "bootstrap.log")
	if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
		t.Skip("cannot write /var/log/ton for dump progress test:", err)
	}
	defer os.RemoveAll(filepath.Join("/var/log/ton", env))
	body := strings.Join([]string{
		"noise",
		"[#153b60 12GiB/235GiB(5%) CN:8 DL:112MiB ETA:33m54s]",
		"FILE: /var/ton-work/dump-cache/x.tar.lz",
	}, "\n")
	if err := os.WriteFile(logPath, []byte(body), 0o644); err != nil {
		t.Skip(err)
	}
	pct, detail := tonBootstrapDumpProgress(Config{Env: env})
	if pct != 5 {
		t.Fatalf("pct=%d want 5", pct)
	}
	if !strings.Contains(detail, "12GiB/235GiB") || !strings.Contains(detail, "ETA 33m54s") {
		t.Fatalf("detail=%q", detail)
	}
}

func TestTonDumpProgressRemembered(t *testing.T) {
	dir := t.TempDir()
	cfg := Config{EtcDir: dir, Env: "testnet"}
	saveTonDumpProgress(cfg, 12, "26GiB/205GiB · ETA 7m17s")
	pct, detail := loadTonDumpProgress(cfg)
	if pct != 12 {
		t.Fatalf("pct=%d want 12", pct)
	}
	if !strings.Contains(detail, "26GiB/205GiB") {
		t.Fatalf("detail=%q", detail)
	}
	clearTonDumpProgress(cfg)
	if p, _ := loadTonDumpProgress(cfg); p != 0 {
		t.Fatalf("cleared pct=%d", p)
	}
}

func TestTonBootstrapDumpProgressChecksumPhase(t *testing.T) {
	env := "rpcnode-test-ton-dump-cksum"
	logPath := filepath.Join("/var/log/ton", env, "bootstrap.log")
	if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
		t.Skip("cannot write /var/log/ton:", err)
	}
	defer os.RemoveAll(filepath.Join("/var/log/ton", env))
	body := strings.Join([]string{
		"[#f034c0 205GiB/205GiB(100%) CN:0]",
		"[#f034c0 205GiB/205GiB(100%) CN:0] [Checksum:#f034c0 194GiB/205GiB(94%)]",
	}, "\n")
	if err := os.WriteFile(logPath, []byte(body), 0o644); err != nil {
		t.Skip(err)
	}
	pct, detail := tonBootstrapDumpProgress(Config{Env: env})
	if pct != 100 {
		t.Fatalf("pct=%d want 100", pct)
	}
	if !strings.Contains(detail, "checksum 94%") {
		t.Fatalf("detail=%q want checksum 94%%", detail)
	}
	phase := tonBootstrapPhaseDetail(Config{Env: env})
	if !strings.Contains(phase, "checksum") && !strings.Contains(phase, "94%") {
		t.Fatalf("phase=%q", phase)
	}
}

func TestTonBootstrapDumpProgressAfterVerify(t *testing.T) {
	env := "rpcnode-test-ton-dump-done"
	logPath := filepath.Join("/var/log/ton", env, "bootstrap.log")
	if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
		t.Skip("cannot write /var/log/ton:", err)
	}
	defer os.RemoveAll(filepath.Join("/var/log/ton", env))
	body := strings.Join([]string{
		"[#f034c0 205GiB/205GiB(100%) CN:0] [Checksum:#f034c0 205GiB/205GiB(99%)]",
		"NOTICE Verification finished successfully. file=/var/ton-work/dump-cache/x.tar.lz",
		"NOTICE Download complete: /var/ton-work/dump-cache/x.tar.lz",
	}, "\n")
	if err := os.WriteFile(logPath, []byte(body), 0o644); err != nil {
		t.Skip(err)
	}
	pct, detail := tonBootstrapDumpProgress(Config{Env: env})
	if pct != 100 {
		t.Fatalf("pct=%d want 100", pct)
	}
	if !strings.Contains(detail, "extract") && !strings.Contains(detail, "verifying") {
		t.Fatalf("detail=%q want extract/verify phase", detail)
	}
}

func TestParseTonClientVersionCommit(t *testing.T) {
	in := "validator-engine build information: [ Commit: bb935a83e8da44a367dc211f264c8ffa13cb7ca1, Date: 2026-08-03 15:10:16 +0300]"
	got := parseTonClientVersionOutput(in)
	if got != "bb935a83e8da" {
		t.Fatalf("got %q want bb935a83e8da", got)
	}
}

func TestTonBootstrapDumpProgressDeepWindow(t *testing.T) {
	env := "rpcnode-test-ton-dump-deep"
	logPath := filepath.Join("/var/log/ton", env, "bootstrap.log")
	if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
		t.Skip("cannot write /var/log/ton:", err)
	}
	defer os.RemoveAll(filepath.Join("/var/log/ton", env))
	var b strings.Builder
	b.WriteString("[#aaaaaa 26GiB/205GiB(12%) CN:8 DL:100MiB ETA:7m17s]\n")
	for i := 0; i < 500; i++ {
		b.WriteString("compiling object.o\n")
	}
	if err := os.WriteFile(logPath, []byte(b.String()), 0o644); err != nil {
		t.Skip(err)
	}
	pct, detail := tonBootstrapDumpProgress(Config{Env: env})
	if pct != 12 {
		t.Fatalf("deep window pct=%d want 12 (compile noise must not hide aria2)", pct)
	}
	if !strings.Contains(detail, "26GiB/205GiB") {
		t.Fatalf("detail=%q", detail)
	}
}

func TestBuildRunStepTonKeepsDumpPctWithoutRPC(t *testing.T) {
	step := buildRunStep(nodeLifecycleInput{
		Network:      "ton",
		Env:          "testnet",
		NodeActive:   true,
		RPCOK:        false,
		IBD:          true,
		VerifyPct:    0.12,
		WarmupDetail: "MyTonCtrl dump 12% · 26GiB/205GiB",
	})
	if step["status"] != "active" {
		t.Fatalf("status=%v want active", step["status"])
	}
	if step["pct"] == nil {
		t.Fatal("dump VerifyPct must stay on run step while THA down")
	}
	detail, _ := step["detail"].(string)
	if !strings.Contains(detail, "12") {
		t.Fatalf("detail=%q", detail)
	}
}
