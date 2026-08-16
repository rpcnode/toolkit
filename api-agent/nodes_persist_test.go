package main

import "testing"

func TestHostRegisterDecisionNeverTouchesTip(t *testing.T) {
	cases := []struct {
		name                   string
		env                    string
		agent, stored, mainnet int
	}{
		{"mainnet bitcoin leaf", "mainnet", 39390, 39090, 39390},
		{"mainnet stellar leaf", "mainnet", 40990, 39090, 0},
		{"mainnet empty tip", "mainnet", 39390, 0, 0},
		{"regtest", "regtest", 39393, 39090, 39390},
		{"signet", "signet", 39391, 39090, 39390},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if hostRegisterDecision(tc.env, tc.agent, tc.stored, tc.mainnet) {
				t.Fatal("leaf provision must never update host tip agent.port / register URL")
			}
		})
	}
}
