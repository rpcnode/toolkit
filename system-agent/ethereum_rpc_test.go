package main

import "testing"

func TestEthSyncVerificationPct(t *testing.T) {
	cases := []struct {
		name     string
		cur, hi  int64
		syncing  bool
		want     float64
	}{
		{"synced", 0, 0, false, 100},
		{"synced_with_height", 12_000_000, 0, false, 100},
		{"early", 100, 1000, true, 10},
		{"mid", 5_000_000, 10_000_000, true, 50},
		{"near", 999, 1000, true, 99.9},
		{"cap", 1100, 1000, true, 99.9},
		{"unknown_highest", 42, 0, true, 0},
		{"zero_current", 0, 1000, true, 0},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := ethSyncVerificationPct(tc.cur, tc.hi, tc.syncing)
			if got != tc.want {
				t.Fatalf("got %v want %v", got, tc.want)
			}
		})
	}
}
