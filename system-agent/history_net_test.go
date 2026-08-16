package main

import (
	"strings"
	"testing"
)

func TestSkipNetIface(t *testing.T) {
	skip := []string{"lo", "veth0", "docker0", "br-abc", "virbr0", "tun0", "wg0"}
	keep := []string{"eth0", "ens3", "enp1s0", "bond0", "eno1"}
	for _, n := range skip {
		if !skipNetIface(n) {
			t.Fatalf("expected skip %s", n)
		}
	}
	for _, n := range keep {
		if skipNetIface(n) {
			t.Fatalf("expected keep %s", n)
		}
	}
}

func TestParseNetDevFieldLayout(t *testing.T) {
	sample := `
    lo: 1000 0 0 0 0 0 0 0 2000 0 0 0 0 0 0 0
  eth0: 1000000 1 0 0 0 0 0 0 500000 1 0 0 0 0 0 0
  veth1: 999 0 0 0 0 0 0 0 999 0 0 0 0 0 0 0
`
	rx, tx := uint64(0), uint64(0)
	ok := false
	for _, line := range strings.Split(sample, "\n") {
		line = strings.TrimSpace(line)
		if line == "" || !strings.Contains(line, ":") {
			continue
		}
		parts := strings.SplitN(line, ":", 2)
		iface := strings.TrimSpace(parts[0])
		if skipNetIface(iface) {
			continue
		}
		fields := strings.Fields(parts[1])
		if len(fields) < 9 {
			continue
		}
		var r, tb uint64
		for i, s := range []string{fields[0], fields[8]} {
			var n uint64
			for j := 0; j < len(s); j++ {
				n = n*10 + uint64(s[j]-'0')
			}
			if i == 0 {
				r = n
			} else {
				tb = n
			}
		}
		rx += r
		tx += tb
		ok = true
	}
	if !ok || rx != 1_000_000 || tx != 500_000 {
		t.Fatalf("rx=%d tx=%d ok=%v", rx, tx, ok)
	}
}
