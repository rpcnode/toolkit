package main

import "testing"

func TestIsLeafAgentPortTronAndTip(t *testing.T) {
	if !isLeafAgentPort(39190) || !isLeafAgentPort(39390) {
		t.Fatal("expected tron/bitcoin leaf Agent API ports")
	}
	// Host tip listens (e.g. 47890) must never be classified as leaf.
	if isLeafAgentPort(47890) || isLeafAgentPort(39090) || isLeafAgentPort(0) {
		t.Fatal("tip / public RPC ports must not be leaf Agent API")
	}
}

func TestAgentURLPort(t *testing.T) {
	if got := agentURLPort("http://140.150.232.20:39190"); got != 39190 {
		t.Fatalf("got %d", got)
	}
	if got := agentURLPort("http://140.150.232.20:47890"); got != 47890 {
		t.Fatalf("got %d", got)
	}
}
