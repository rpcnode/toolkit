package main

import "testing"

func TestAgentVersionOutdated(t *testing.T) {
	cases := []struct {
		local, remote string
		want          bool
	}{
		{"0.3.8", "0.3.10", true},
		{"0.3.10", "0.3.10", false},
		{"0.3.11", "0.3.10", false},
		{"v0.3.8", "0.3.10", true},
		{"", "0.3.10", false},
		{"0.3.8", "", false},
		{"1.0.0", "1.0.1", true},
		{"1.2", "1.2.0", false},
	}
	for _, tc := range cases {
		got := agentVersionOutdated(tc.local, tc.remote)
		if got != tc.want {
			t.Errorf("outdated(%q, %q)=%v want %v", tc.local, tc.remote, got, tc.want)
		}
	}
}
