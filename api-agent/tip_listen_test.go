package main

import "testing"

func TestDefaultHostTipPortAvoidsCatalog(t *testing.T) {
	if defaultHostTipPort <= 0 {
		t.Fatal("defaultHostTipPort unset")
	}
	if isCanonicalPerNodeAgentPort(defaultHostTipPort) {
		t.Fatalf("tip %d collides with a leaf Agent API port", defaultHostTipPort)
	}
	for _, p := range builtinPortProfiles() {
		if p.Public == defaultHostTipPort || p.Agent == defaultHostTipPort {
			t.Fatalf("tip %d collides with %s/%s public=%d agent=%d",
				defaultHostTipPort, p.Network, p.Env, p.Public, p.Agent)
		}
	}
}
