package main

import (
	"os"
	"regexp"
	"strconv"
	"strings"
)

var (
	reMithrilStep   = regexp.MustCompile(`(?i)(\d+)\s*/\s*7\s*-`)
	reMithrilFiles  = regexp.MustCompile(`(?i)Files:\s*([0-9,]+)\s*/\s*([0-9,]+)`)
	reMithrilBytes  = regexp.MustCompile(`(?i)([0-9]+(?:\.[0-9]+)?)\s*(KiB|MiB|GiB)\s*/\s*([0-9]+(?:\.[0-9]+)?)\s*(KiB|MiB|GiB)`)
	reMithrilDone   = regexp.MustCompile(`(?i)successfully unpacked|Cardano database snapshot`)
)

func cardanoMithrilSnapshotRunning(cfg Config) bool {
	data := strings.TrimSpace(cfg.DataDir)
	out, err := runCmd(2*time.Second, "bash", "-lc",
		`pgrep -af 'mithril-client' | grep -F 'cardano-db' | grep -v grep | head -1`)
	if err != nil || strings.TrimSpace(out) == "" {
		return false
	}
	if data != "" && !strings.Contains(out, data) && !strings.Contains(out, "cardano") {
		return false
	}
	return true
}

func cardanoMithrilSnapshotPct(cfg Config) (float64, bool) {
	if st := readJSONFile(cfg.SnapshotState); st != nil {
		switch v := st["pct"].(type) {
		case float64:
			if v >= 0 && v <= 100 {
				return v, true
			}
		case string:
			if p, err := strconv.ParseFloat(strings.TrimSpace(v), 64); err == nil && p >= 0 && p <= 100 {
				return p, true
			}
		}
	}
	texts := []string{}
	if p := strings.TrimSpace(cfg.SnapshotLog); p != "" {
		if b, err := os.ReadFile(p); err == nil && len(b) > 0 {
			texts = append(texts, string(b))
		}
	}
	if snip := journalUnitGrepSnippet(cfg.SnapshotService, 80, `Files:|/7 -|MiB/|unpacked`); snip != "" {
		texts = append(texts, snip)
	}
	var best float64
	var ok bool
	for _, t := range texts {
		if p, hit := parseCardanoMithrilProgress(t); hit && (!ok || p >= best) {
			best, ok = p, true
		}
	}
	return best, ok
}

// parseCardanoMithrilProgress — official client prints "N/7 - …" plus
// "Files: x/y" and "A MiB/B MiB" during step 3. Map onto 0–99.9 until unpacked.
func parseCardanoMithrilProgress(text string) (float64, bool) {
	if reMithrilDone.MatchString(text) && strings.Contains(strings.ToLower(text), "unpacked") {
		return 99.9, true
	}
	var best float64
	var ok bool
	consider := func(p float64) {
		if p > 99.9 {
			p = 99.9
		}
		if p < 0 {
			return
		}
		if !ok || p >= best {
			best, ok = p, true
		}
	}
	for _, ln := range strings.Split(text, "\n") {
		if m := reMithrilStep.FindStringSubmatch(ln); len(m) == 2 {
			n, _ := strconv.Atoi(m[1])
			if n >= 1 && n <= 7 {
				// Step n just started → (n-1)/7 of the job.
				consider(float64(n-1) / 7 * 100)
			}
		}
		if m := reMithrilFiles.FindStringSubmatch(ln); len(m) == 3 {
			x, y := parseCommaInt64(m[1]), parseCommaInt64(m[2])
			if y > 0 && x >= 0 {
				// Step 3 occupies ~20–80%.
				consider(20 + float64(x)/float64(y)*60)
			}
		}
		if m := reMithrilBytes.FindStringSubmatch(ln); len(m) == 5 {
			a := parseSizeToBytes(m[1], m[2])
			b := parseSizeToBytes(m[3], m[4])
			if b > 0 && a >= 0 {
				consider(20 + a/b*60)
			}
		}
	}
	return best, ok
}

func parseSizeToBytes(n, unit string) float64 {
	v, err := strconv.ParseFloat(n, 64)
	if err != nil || v < 0 {
		return 0
	}
	switch strings.ToLower(unit) {
	case "kib":
		return v * 1024
	case "mib":
		return v * 1024 * 1024
	case "gib":
		return v * 1024 * 1024 * 1024
	default:
		return v
	}
}
