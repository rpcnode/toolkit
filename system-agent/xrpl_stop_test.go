package main

import (
	"strings"
	"testing"
)

func TestPatchXRPLUnitGracefulStop(t *testing.T) {
	src := `[Service]
ExecStart=/usr/bin/xrpld --conf /etc/xrpl/mainnet/xrpld.cfg
Restart=on-failure
RestartSec=10
`
	got, ok := patchXRPLUnitGracefulStop(src, "/usr/bin/xrpld", "/etc/xrpl/mainnet/xrpld.cfg")
	if !ok {
		t.Fatal("want patch")
	}
	if !strings.Contains(got, "ExecStop=-/usr/bin/timeout 15 /usr/bin/xrpld --conf /etc/xrpl/mainnet/xrpld.cfg server_stop") {
		t.Fatalf("stop:\n%s", got)
	}
	if !strings.Contains(got, "TimeoutStopSec=45") {
		t.Fatalf("timeout:\n%s", got)
	}
	_, ok = patchXRPLUnitGracefulStop(got, "/usr/bin/xrpld", "/etc/xrpl/mainnet/xrpld.cfg")
	if ok {
		t.Fatal("second patch must no-op")
	}

	old := `[Service]
ExecStart=/usr/bin/xrpld --conf /etc/xrpl/mainnet/xrpld.cfg
ExecStop=/usr/bin/timeout 15 /usr/bin/xrpld --conf /etc/xrpl/mainnet/xrpld.cfg server_stop
`
	up, ok := patchXRPLUnitGracefulStop(old, "/usr/bin/xrpld", "/etc/xrpl/mainnet/xrpld.cfg")
	if !ok || !strings.Contains(up, "ExecStop=-/usr/bin/timeout") {
		t.Fatalf("want '-' so dead server_stop is not a unit failure:\n%s", up)
	}
}

func TestXRPLServerStopNoise(t *testing.T) {
	if !xrplServerStopNoise(`"error_what" : "no response from server. Please ensure that the xrpld server is running in another process." · ExecMainCode=1`) {
		t.Fatal("ExecStop against a dead xrpld is not a start fault")
	}
	if xrplServerStopNoise("SHAMapStore state db error") {
		t.Fatal("real start fault")
	}
}

func TestXRPLDHoldsNuDBEmpty(t *testing.T) {
	if xrpldHoldsNuDB("") || xrpldHoldsNuDB("/tmp/does-not-exist-nudb") {
		t.Fatal("missing datadir is not held")
	}
}
