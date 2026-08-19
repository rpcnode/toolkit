package main

import (
	"sort"
	"strconv"
	"strings"
	"unicode"
)

type diskDevSnap struct {
	Name   string
	Reads  uint64
	Writes uint64
	RSect  uint64
	WSect  uint64
	IOMs   uint64
}

type diskDevRate struct {
	Name      string  `json:"name"`
	ReadIOPS  float64 `json:"read_iops"`
	WriteIOPS float64 `json:"write_iops"`
	ReadMBs   float64 `json:"read_mb_s"`
	WriteMBs  float64 `json:"write_mb_s"`
	UtilPct   float64 `json:"util_pct"`
	FreeGB    float64 `json:"free_gb,omitempty"`
	TotalGB   float64 `json:"total_gb,omitempty"`
	UsedPct   float64 `json:"used_pct,omitempty"`
	Mount     string  `json:"mount,omitempty"`
}

type diskRates struct {
	ReadIOPS  float64
	WriteIOPS float64
	ReadMBs   float64
	WriteMBs  float64
	UtilPct   float64
	BusyName  string
	Devices   []diskDevRate
}

func readDiskstatsHost() ([]diskDevSnap, bool) {
	b, err := readProcPathHost("diskstats")
	if err != nil {
		return nil, false
	}
	devs := parseDiskstats(string(b))
	return devs, len(devs) > 0
}

func parseDiskstats(s string) []diskDevSnap {
	var out []diskDevSnap
	for _, line := range strings.Split(s, "\n") {
		fields := strings.Fields(line)
		if len(fields) < 14 {
			continue
		}
		name := fields[2]
		if !isWholeDiskName(name) {
			continue
		}
		reads, errR := strconv.ParseUint(fields[3], 10, 64)
		writes, errW := strconv.ParseUint(fields[7], 10, 64)
		rsect, errRS := strconv.ParseUint(fields[5], 10, 64)
		wsect, errWS := strconv.ParseUint(fields[9], 10, 64)
		ioMs, errIO := strconv.ParseUint(fields[12], 10, 64)
		if errR != nil || errW != nil || errRS != nil || errWS != nil || errIO != nil {
			continue
		}
		out = append(out, diskDevSnap{
			Name: name, Reads: reads, Writes: writes,
			RSect: rsect, WSect: wsect, IOMs: ioMs,
		})
	}
	return out
}

func isWholeDiskName(name string) bool {
	n := strings.ToLower(strings.TrimSpace(name))
	if n == "" {
		return false
	}
	for _, p := range []string{"loop", "ram", "dm-", "md", "sr", "zram", "nbd", "fd"} {
		if strings.HasPrefix(n, p) {
			return false
		}
	}
	if strings.HasPrefix(n, "nvme") {
		if strings.Contains(n, "p") || strings.Contains(n, "c") {
			return false
		}
		return strings.Contains(n, "n")
	}
	if strings.HasPrefix(n, "mmcblk") {
		rest := strings.TrimPrefix(n, "mmcblk")
		return rest != "" && !strings.Contains(rest, "p") && diskDigitsOnly(rest)
	}
	for _, p := range []string{"sd", "vd", "hd"} {
		if strings.HasPrefix(n, p) && len(n) > len(p) {
			return diskLettersOnly(n[len(p):])
		}
	}
	if strings.HasPrefix(n, "xvd") && len(n) > 3 {
		return diskLettersOnly(n[3:])
	}
	return false
}

func diskLettersOnly(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if !unicode.IsLetter(r) {
			return false
		}
	}
	return true
}

func diskDigitsOnly(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

func diskRatesFromDelta(prev, cur []diskDevSnap, dt float64) diskRates {
	var out diskRates
	if len(cur) == 0 {
		return out
	}
	if dt < 0.2 {
		out.Devices = diskDevicesPlaceholder(cur)
		return out
	}
	prevBy := make(map[string]diskDevSnap, len(prev))
	for _, d := range prev {
		prevBy[d.Name] = d
	}
	var dReads, dWrites, dRSect, dWSect float64
	maxUtil := -1.0
	busy := ""
	devs := make([]diskDevRate, 0, len(cur))
	for _, c := range cur {
		p, ok := prevBy[c.Name]
		if !ok {
			devs = append(devs, diskDevRate{Name: c.Name})
			continue
		}
		var rI, wI, rS, wS, dIOMs float64
		if c.Reads >= p.Reads {
			rI = float64(c.Reads - p.Reads)
		}
		if c.Writes >= p.Writes {
			wI = float64(c.Writes - p.Writes)
		}
		if c.RSect >= p.RSect {
			rS = float64(c.RSect - p.RSect)
		}
		if c.WSect >= p.WSect {
			wS = float64(c.WSect - p.WSect)
		}
		if c.IOMs >= p.IOMs {
			dIOMs = float64(c.IOMs - p.IOMs)
		}
		dReads += rI
		dWrites += wI
		dRSect += rS
		dWSect += wS
		util := dIOMs / (dt * 1000) * 100
		if util < 0 {
			util = 0
		}
		if util > 100 {
			util = 100
		}
		if util > maxUtil {
			maxUtil = util
			busy = c.Name
		}
		devs = append(devs, diskDevRate{
			Name: c.Name,
			ReadIOPS: rI / dt, WriteIOPS: wI / dt,
			ReadMBs: rS * 512 / dt / 1_000_000,
			WriteMBs: wS * 512 / dt / 1_000_000,
			UtilPct: util,
		})
	}
	sortDiskRates(devs)
	out.Devices = devs
	out.ReadIOPS = dReads / dt
	out.WriteIOPS = dWrites / dt
	out.ReadMBs = dRSect * 512 / dt / 1_000_000
	out.WriteMBs = dWSect * 512 / dt / 1_000_000
	if maxUtil >= 0 {
		out.UtilPct = maxUtil
		out.BusyName = busy
	}
	return out
}

func diskDevicesPlaceholder(devs []diskDevSnap) []diskDevRate {
	out := make([]diskDevRate, 0, len(devs))
	for _, d := range devs {
		out = append(out, diskDevRate{Name: d.Name})
	}
	sortDiskRates(out)
	return out
}

func sortDiskRates(devs []diskDevRate) {
	sort.Slice(devs, func(i, j int) bool { return devs[i].Name < devs[j].Name })
}

func diskRatesJSON(devs []diskDevRate) []map[string]any {
	out := make([]map[string]any, 0, len(devs))
	for _, d := range devs {
		out = append(out, map[string]any{
			"name":       d.Name,
			"read_iops":  round1(d.ReadIOPS),
			"write_iops": round1(d.WriteIOPS),
			"read_mb_s":  round2(d.ReadMBs),
			"write_mb_s": round2(d.WriteMBs),
			"util_pct":   round2(d.UtilPct),
			"free_gb":    round1(d.FreeGB),
			"total_gb":   round1(d.TotalGB),
			"used_pct":   round1(d.UsedPct),
			"mount":      d.Mount,
		})
	}
	return out
}

func findDiskRate(devs []diskDevRate, name string) diskDevRate {
	for _, d := range devs {
		if d.Name == name {
			return d
		}
	}
	return diskDevRate{Name: name}
}

func diskNameOrder(samples [][]diskDevRate) []string {
	seen := map[string]bool{}
	var names []string
	for _, devs := range samples {
		for _, d := range devs {
			if d.Name == "" || seen[d.Name] {
				continue
			}
			seen[d.Name] = true
			names = append(names, d.Name)
		}
	}
	sort.Strings(names)
	return names
}
