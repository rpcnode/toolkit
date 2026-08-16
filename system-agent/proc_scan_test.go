package main

import "testing"

func TestProcCmdlineHasSelfExcluded(t *testing.T) {
	// Must not match ourselves; should not panic / hang.
	_, _ = procCmdlineHas("rpcnode-system-agent-this-will-not-match-zzzz")
}
